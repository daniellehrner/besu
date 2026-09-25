/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.plugin.services.storage.codestore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A memory-mapped, content-addressed, append-only store for contract bytecode keyed by code hash.
 *
 * <h2>Durability</h2>
 *
 * {@link #put} appends a record to the log and remembers it in memory; it promises nothing about
 * durability. {@link #sync} forces the log, then writes the index slots for the new records, then
 * advances and forces the index header's {@code logLength}. The on-disk index therefore never
 * refers to log bytes that were not forced first, and {@code logLength} is a durable watermark:
 * everything below it was synced, everything above it was not.
 *
 * <h2>Recovery</h2>
 *
 * On open the log is scanned forward from the watermark. Valid records found there are re-indexed;
 * the first thing that is not a valid record ends the log, and whatever follows is discarded,
 * because nothing past the watermark was ever acknowledged as synced. Without a usable index the
 * whole log is scanned and there is no watermark, so a bad record that is followed by a valid one
 * is reported as corruption instead of being silently cut off.
 *
 * <h2>Threading</h2>
 *
 * Any number of concurrent readers, one writer, guarded by a read-write lock. Segments returned by
 * {@link #get} stay valid until {@link #close} or {@link #clear}; accessing one afterwards throws.
 */
public final class CodeStore implements AutoCloseable {

  /** Length of a code hash in bytes. */
  public static final int HASH_SIZE = 32;

  private static final Logger LOG = LoggerFactory.getLogger(CodeStore.class);
  private static final String LOCK_FILE_NAME = "LOCK";

  private final Path dir;
  private final FileChannel lockChannel;
  private final FileLock fileLock;
  private final ProbeListener probes;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Map<HashKey, Long> pending = new LinkedHashMap<>();
  private final long truncatedBytesOnOpen;
  private final long recoveredRecordsOnOpen;

  private final CodeStoreOptions options;

  private CodeLog log;
  private CodeIndex index;
  private boolean closed;
  private long epoch;

  /** A code hash usable as a map key. */
  private record HashKey(byte[] bytes) {
    @Override
    public boolean equals(final Object other) {
      return other instanceof HashKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }
  }

  private CodeStore(
      final Path dir,
      final FileChannel lockChannel,
      final FileLock fileLock,
      final CodeLog log,
      final CodeIndex index,
      final CodeStoreOptions options,
      final long truncatedBytesOnOpen,
      final long recoveredRecordsOnOpen) {
    this.dir = dir;
    this.lockChannel = lockChannel;
    this.fileLock = fileLock;
    this.log = log;
    this.index = index;
    this.options = options;
    this.probes = options.probeListener();
    this.truncatedBytesOnOpen = truncatedBytesOnOpen;
    this.recoveredRecordsOnOpen = recoveredRecordsOnOpen;
  }

  /**
   * Opens the store in {@code dir} with default options.
   *
   * @param dir the store directory
   * @return the open store
   * @throws IOException if the files cannot be opened
   */
  public static CodeStore open(final Path dir) throws IOException {
    return open(dir, CodeStoreOptions.defaults());
  }

  /**
   * Opens the store in {@code dir}, creating it if absent, and runs crash recovery.
   *
   * @param dir the store directory
   * @param options tunables and MANIFEST values
   * @return the open store
   * @throws IOException if the files cannot be opened
   * @throws CorruptCodeStoreException if the log is damaged below the durable watermark
   */
  public static CodeStore open(final Path dir, final CodeStoreOptions options) throws IOException {
    Files.createDirectories(dir);
    final FileChannel lockChannel =
        FileChannel.open(
            dir.resolve(LOCK_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    CodeLog log = null;
    final CodeIndex[] index = new CodeIndex[1];
    try {
      final FileLock fileLock = lockChannel.tryLock();
      if (fileLock == null) {
        throw new IllegalStateException(dir + " is already open in another process");
      }
      Manifest.loadOrCreate(dir, options);
      log = CodeLog.open(dir, options.logGrowStep());
      final CodeLog openLog = log;

      final Optional<CodeIndex> existing = CodeIndex.openExisting(dir);
      final boolean rebuild = existing.isEmpty();
      index[0] =
          rebuild ? CodeIndex.create(dir, options.initialIndexCapacity()) : existing.orElseThrow();
      final long watermark = index[0].logLength();
      if (watermark > log.sizeAtOpen()) {
        throw new CorruptCodeStoreException(
            String.format(
                "%s is %d bytes but the index covers %d bytes of it",
                dir.resolve(CodeLog.FILE_NAME), log.sizeAtOpen(), watermark));
      }
      if (rebuild && log.sizeAtOpen() > CodeLog.HEADER_SIZE) {
        LOG.info("No usable index in {}, rebuilding it from the log", dir);
      }

      final byte[] hash = new byte[HASH_SIZE];
      final CodeLog.ScanResult scan =
          log.scan(
              watermark,
              log.sizeAtOpen(),
              (segment, hashOffset, payloadOffset, length) -> {
                MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, hashOffset, hash, 0, HASH_SIZE);
                index[0] = reindex(index[0], openLog, hash, payloadOffset);
              });

      final long dataEnd = log.dataEnd(scan.validEnd());
      if (rebuild && log.hasValidRecordIn(scan.validEnd() + 8, dataEnd)) {
        throw new CorruptCodeStoreException(
            String.format(
                "%s has an invalid record at offset %d followed by valid ones",
                dir.resolve(CodeLog.FILE_NAME), scan.validEnd()));
      }
      final long truncated = log.recoverTail(scan.validEnd(), dataEnd);
      if (truncated > 0) {
        LOG.info(
            "Truncated {} bytes of unsynced partial data from the end of {}",
            truncated,
            dir.resolve(CodeLog.FILE_NAME));
      }
      // A clean close trims the file to the log, so any other length means the writer died.
      final boolean unclean = log.sizeAtOpen() != scan.validEnd();
      if (unclean && log.sizeAtOpen() > CodeLog.HEADER_SIZE) {
        LOG.info(
            "{} was not closed cleanly, re-indexed {} records written after the last sync",
            dir,
            scan.records());
      }
      if (!rebuild && unclean && index[0].hasTornSlot(watermark, log)) {
        LOG.info("The index in {} has a half-written slot, rebuilding it from the log", dir);
        index[0].close();
        index[0] = CodeIndex.create(dir, options.initialIndexCapacity());
        final CodeLog.ScanResult full =
            log.scan(
                CodeLog.HEADER_SIZE,
                log.end(),
                (segment, hashOffset, payloadOffset, length) -> {
                  MemorySegment.copy(
                      segment, ValueLayout.JAVA_BYTE, hashOffset, hash, 0, HASH_SIZE);
                  index[0] = reindex(index[0], openLog, hash, payloadOffset);
                });
        if (full.validEnd() != log.end()) {
          throw new CorruptCodeStoreException(
              String.format(
                  "%s has an invalid record at offset %d, below the synced length %d",
                  dir.resolve(CodeLog.FILE_NAME), full.validEnd(), log.end()));
        }
      }
      if (rebuild || unclean || scan.records() > 0 || truncated > 0) {
        index[0].recount();
        index[0].commit(log.end());
      }
      index[0].load();
      if (options.preload()) {
        log.load();
      }
      return new CodeStore(
          dir, lockChannel, fileLock, log, index[0], options, truncated, scan.records());
    } catch (final Throwable t) {
      if (index[0] != null) {
        index[0].close();
      }
      if (log != null) {
        log.abandon();
      }
      lockChannel.close();
      throw t;
    }
  }

  private static CodeIndex reindex(
      final CodeIndex index, final CodeLog log, final byte[] hash, final long payloadOffset) {
    final CodeIndex target = index.needsGrowFor(1) ? grow(index) : index;
    target.insertIfAbsent(hash, payloadOffset, log::isHashAt);
    return target;
  }

  private static CodeIndex grow(final CodeIndex index) {
    try {
      final CodeIndex grown = index.grown();
      index.close();
      return grown;
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to grow the code index", e);
    }
  }

  /**
   * Zero-copy, read-only view of the code stored for {@code codeHash}. The segment is valid until
   * the store is closed.
   *
   * @param codeHash the 32-byte code hash
   * @return the code, or empty if the hash is not stored
   */
  public Optional<MemorySegment> get(final byte[] codeHash) {
    checkHash(codeHash);
    lock.readLock().lock();
    try {
      checkOpen();
      final long offset = offsetOf(codeHash);
      return offset < 0 ? Optional.empty() : Optional.of(log.payload(offset));
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Tells whether code is stored for {@code codeHash}.
   *
   * @param codeHash the 32-byte code hash
   * @return true if present
   */
  public boolean contains(final byte[] codeHash) {
    checkHash(codeHash);
    lock.readLock().lock();
    try {
      checkOpen();
      return offsetOf(codeHash) >= 0;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Stores {@code code} under {@code codeHash}; a no-op if the hash is already present. The store
   * does not check that the hash matches the code and enforces no size cap. Not durable until
   * {@link #sync}.
   *
   * @param codeHash the 32-byte code hash, not all zero
   * @param code the bytecode
   */
  public void put(final byte[] codeHash, final byte[] code) {
    checkHash(codeHash);
    lock.writeLock().lock();
    try {
      checkOpen();
      if (offsetOf(codeHash) >= 0) {
        return;
      }
      pending.put(new HashKey(codeHash.clone()), log.append(codeHash, code));
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Stores and syncs a batch under one hold of the write lock. A crash part-way may keep a prefix
   * of the batch; that is safe only because entries are content-addressed, so a caller must make
   * this batch durable before it commits anything that refers to it.
   *
   * @param batch code keyed by code hash
   */
  public void putAllAndSync(final Iterable<Map.Entry<byte[], byte[]>> batch) {
    lock.writeLock().lock();
    try {
      checkOpen();
      for (final Map.Entry<byte[], byte[]> entry : batch) {
        checkHash(entry.getKey());
        if (offsetOf(entry.getKey()) < 0) {
          pending.put(
              new HashKey(entry.getKey().clone()), log.append(entry.getKey(), entry.getValue()));
        }
      }
      syncLocked();
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Removes every entry, durably. Views handed out earlier become invalid.
   *
   * <p>The index is deleted first, then the log is replaced, then a new index is created. A crash
   * in between leaves a log without an index, which the next open rebuilds: the clear either
   * happened or it did not.
   *
   * @throws IOException if the files cannot be replaced
   */
  public void clear() throws IOException {
    lock.writeLock().lock();
    try {
      checkOpen();
      pending.clear();
      epoch++;
      index.close();
      log.abandon();
      Files.deleteIfExists(dir.resolve(CodeIndex.FILE_NAME));
      Durable.syncDirectory(dir);
      CodeLog.reset(dir);
      log = CodeLog.open(dir, options.logGrowStep());
      log.recoverTail(CodeLog.HEADER_SIZE, CodeLog.HEADER_SIZE);
      index = CodeIndex.create(dir, options.initialIndexCapacity());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Lazily streams every entry in insertion order, copying each out of the mapping. Entries added
   * after the call are not included. The stream fails if the store is cleared under it.
   *
   * @return code hash and code pairs
   */
  public Stream<Map.Entry<byte[], byte[]>> stream() {
    final long limit;
    final long startEpoch;
    lock.readLock().lock();
    try {
      checkOpen();
      limit = log.end();
      startEpoch = epoch;
    } finally {
      lock.readLock().unlock();
    }
    final Spliterator<Map.Entry<byte[], byte[]>> entries =
        new Spliterators.AbstractSpliterator<>(
            Long.MAX_VALUE, Spliterator.NONNULL | Spliterator.DISTINCT) {
          private long payloadOffset = CodeLog.HEADER_SIZE + CodeLog.REC_HEADER_SIZE;

          @Override
          public boolean tryAdvance(final Consumer<? super Map.Entry<byte[], byte[]>> action) {
            lock.readLock().lock();
            try {
              checkOpen();
              if (epoch != startEpoch) {
                throw new ConcurrentModificationException("code store was cleared");
              }
              while (payloadOffset < limit) {
                final long current = payloadOffset;
                payloadOffset = log.nextRecord(current) + CodeLog.REC_HEADER_SIZE;
                final byte[] hash = log.hashAt(current);
                // A later duplicate of a hash is not the entry readers see; skip it.
                if (offsetOf(hash) == current) {
                  action.accept(
                      Map.entry(hash, log.payload(current).toArray(ValueLayout.JAVA_BYTE)));
                  return true;
                }
              }
              return false;
            } finally {
              lock.readLock().unlock();
            }
          }
        };
    return StreamSupport.stream(entries, false);
  }

  /** Makes every preceding {@link #put} durable. */
  public void sync() {
    lock.writeLock().lock();
    try {
      checkOpen();
      syncLocked();
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void syncLocked() {
    if (pending.isEmpty()) {
      return;
    }
    log.force();
    while (index.needsGrowFor(pending.size())) {
      index = grow(index);
    }
    pending.forEach((hash, offset) -> index.insertIfAbsent(hash.bytes(), offset, log::isHashAt));
    index.commit(log.end());
    pending.clear();
  }

  /**
   * Visits every entry. Order is unspecified.
   *
   * @param visitor receives each code hash and a view of its code
   */
  public void forEach(final BiConsumer<byte[], MemorySegment> visitor) {
    lock.readLock().lock();
    try {
      checkOpen();
      index.forEach(offset -> visitor.accept(log.hashAt(offset), log.payload(offset)));
      pending.forEach((hash, offset) -> visitor.accept(hash.bytes().clone(), log.payload(offset)));
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Checks every record's CRC and every index slot against the log.
   *
   * @return the number of records in the log
   * @throws CorruptCodeStoreException on any inconsistency
   */
  public long verify() {
    lock.readLock().lock();
    try {
      checkOpen();
      final CodeLog.ScanResult scan = log.scan(CodeLog.HEADER_SIZE, log.end(), null);
      if (scan.invalidRecord() || scan.validEnd() != log.end()) {
        throw new CorruptCodeStoreException(
            String.format(
                "%s: invalid record at offset %d, log ends at %d",
                dir.resolve(CodeLog.FILE_NAME), scan.validEnd(), log.end()));
      }
      index.forEach(
          offset -> {
            if (offset >= log.end()
                || index.find(log.hashAt(offset), log::isHashAt, ProbeListener.NONE) != offset) {
              throw new CorruptCodeStoreException(
                  dir.resolve(CodeIndex.FILE_NAME) + ": slot for offset " + offset + " is broken");
            }
          });
      return scan.records();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Number of distinct code hashes stored.
   *
   * @return the entry count
   */
  public long entries() {
    lock.readLock().lock();
    try {
      return index.count() + pending.size();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Length of the log in bytes, headers and padding included.
   *
   * @return the log length
   */
  public long logBytes() {
    lock.readLock().lock();
    try {
      return log.end();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Bytes of unsynced partial data that recovery discarded when this store was opened.
   *
   * @return the discarded byte count
   */
  public long truncatedBytesOnOpen() {
    return truncatedBytesOnOpen;
  }

  /**
   * Records beyond the index watermark that recovery re-indexed when this store was opened.
   *
   * @return the re-indexed record count
   */
  public long recoveredRecordsOnOpen() {
    return recoveredRecordsOnOpen;
  }

  private long offsetOf(final byte[] codeHash) {
    if (!pending.isEmpty()) {
      final Long offset = pending.get(new HashKey(codeHash));
      if (offset != null) {
        return offset;
      }
    }
    return index.find(codeHash, log::isHashAt, probes);
  }

  private static void checkHash(final byte[] codeHash) {
    if (codeHash.length != HASH_SIZE) {
      throw new IllegalArgumentException("code hash must be " + HASH_SIZE + " bytes");
    }
    for (final byte b : codeHash) {
      if (b != 0) {
        return;
      }
    }
    throw new IllegalArgumentException("the all-zero code hash is reserved for empty index slots");
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("code store is closed");
    }
  }

  /** Syncs and closes the store. Idempotent. */
  @Override
  public void close() throws IOException {
    lock.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      syncLocked();
      closed = true;
      try {
        final long entries = index.count();
        final long bytes = log.end();
        index.close();
        log.close();
        LOG.info("Closed the code store in {} cleanly: {} entries, {} bytes", dir, entries, bytes);
      } finally {
        fileLock.release();
        lockChannel.close();
      }
    } finally {
      lock.writeLock().unlock();
    }
  }
}
