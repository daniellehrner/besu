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
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * The append-only record log ({@code code.log}). All multi-byte fields are big-endian.
 *
 * <p>The file is mapped read-write and grown in fixed steps, so while the store is open the file is
 * longer than the log it holds and everything past {@link #end()} is zero. That invariant is what
 * lets a forward scan find the end of the log: a zero record magic. A clean close truncates the
 * file back to the exact log length.
 *
 * <p>Growing maps the whole file again rather than closing the previous mapping. Superseded
 * mappings stay alive until {@link #close()}, so a segment handed out by {@link #payload} remains
 * valid for the lifetime of the store no matter how much the log grows afterwards.
 *
 * <p>Not thread-safe; {@link CodeStore} serialises writers and excludes them from readers.
 */
final class CodeLog implements AutoCloseable {

  static final String FILE_NAME = "code.log";
  static final int HEADER_SIZE = 64;
  static final int MAGIC = 0x42435331; // "BCS1"
  static final int VERSION = 1;
  static final int REC_MAGIC = 0x52454300; // "REC\0"

  /** recMagic + length + codeHash. */
  static final int REC_HEADER_SIZE = 4 + 4 + CodeStore.HASH_SIZE;

  private static final int CRC_SIZE = 4;
  private static final long CRC_CHUNK = 1L << 30;

  static final ValueLayout.OfInt INT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
  static final ValueLayout.OfLong LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

  /** Receives every valid record met by a scan. */
  @FunctionalInterface
  interface RecordVisitor {
    void visit(MemorySegment log, long hashOffset, long payloadOffset, long length);
  }

  /**
   * Outcome of a forward scan.
   *
   * @param validEnd offset just past the last valid record
   * @param records number of valid records visited
   * @param invalidRecord true if the scan stopped at bytes that claim to be a record but are not a
   *     valid one, false if it stopped at a zero magic or at the limit
   */
  record ScanResult(long validEnd, long records, boolean invalidRecord) {}

  private final Path path;
  private final FileChannel channel;
  private final long growStep;
  private final long sizeAtOpen;
  private final List<Arena> arenas = new ArrayList<>();

  private MemorySegment segment;
  private long end;
  private long forcedUpTo;

  private CodeLog(
      final Path path, final FileChannel channel, final long growStep, final long sizeAtOpen) {
    this.path = path;
    this.channel = channel;
    this.growStep = growStep;
    this.sizeAtOpen = sizeAtOpen;
  }

  /**
   * Opens the log, creating it if absent. The caller must establish the log end with {@link #scan}
   * and {@link #recoverTail} before appending.
   */
  static CodeLog open(final Path dir, final long growStep) throws IOException {
    if (growStep <= 0 || (growStep & 7) != 0) {
      throw new IllegalArgumentException("growStep must be a positive multiple of 8");
    }
    final Path path = dir.resolve(FILE_NAME);
    if (Files.notExists(path)) {
      create(dir, path);
    }
    final FileChannel channel =
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    try {
      final long size = channel.size();
      if (size < HEADER_SIZE) {
        throw new CorruptCodeStoreException(
            path + " is " + size + " bytes, shorter than the log header");
      }
      final CodeLog log = new CodeLog(path, channel, growStep, size);
      log.map(roundUp(size, growStep));
      log.validateHeader();
      log.end = HEADER_SIZE;
      log.forcedUpTo = HEADER_SIZE;
      return log;
    } catch (final Throwable t) {
      channel.close();
      throw t;
    }
  }

  private static void create(final Path dir, final Path path) throws IOException {
    final Path tmp = dir.resolve(FILE_NAME + ".new");
    final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
    header.putInt(0, MAGIC).putInt(4, VERSION);
    try (FileChannel ch =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      while (header.hasRemaining()) {
        ch.write(header);
      }
      ch.force(true);
    }
    Durable.rename(tmp, path);
  }

  private void validateHeader() {
    final int magic = segment.get(INT, 0);
    final int version = segment.get(INT, 4);
    if (magic != MAGIC) {
      throw new CorruptCodeStoreException(
          String.format("%s: bad log magic 0x%08x, expected 0x%08x", path, magic, MAGIC));
    }
    if (version != VERSION) {
      throw new CorruptCodeStoreException(
          path + ": unsupported log version " + version + ", expected " + VERSION);
    }
  }

  private void map(final long size) throws IOException {
    final Arena arena = Arena.ofShared();
    arenas.add(arena);
    segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
  }

  /** File length when the log was opened, before mapping extended it. */
  long sizeAtOpen() {
    return sizeAtOpen;
  }

  long end() {
    return end;
  }

  /**
   * Scans records forward from {@code from}, never reading at or past {@code limit}.
   *
   * @param from offset of a record start
   * @param limit exclusive upper bound for the scan
   * @param visitor receives each valid record, may be null
   */
  ScanResult scan(final long from, final long limit, final RecordVisitor visitor) {
    long offset = from;
    long records = 0;
    while (offset + REC_HEADER_SIZE + CRC_SIZE <= limit) {
      final int magic = segment.get(INT, offset);
      if (magic == 0) {
        return new ScanResult(offset, records, false);
      }
      if (magic != REC_MAGIC) {
        return new ScanResult(offset, records, true);
      }
      final long length = Integer.toUnsignedLong(segment.get(INT, offset + 4));
      final long total = recordSize(length);
      if (offset + total > limit) {
        return new ScanResult(offset, records, true);
      }
      final long hashOffset = offset + 8;
      final long payloadOffset = offset + REC_HEADER_SIZE;
      final int storedCrc = segment.get(INT, payloadOffset + length);
      if (storedCrc != crc(segment, hashOffset, CodeStore.HASH_SIZE + length)) {
        return new ScanResult(offset, records, true);
      }
      if (visitor != null) {
        visitor.visit(segment, hashOffset, payloadOffset, length);
      }
      records++;
      offset += total;
    }
    return new ScanResult(offset, records, false);
  }

  /**
   * True if a CRC-valid record starts anywhere in {@code [from, limit)}. Used to tell a torn tail
   * (nothing valid follows) from corruption in the middle of the log.
   */
  boolean hasValidRecordIn(final long from, final long limit) {
    for (long offset = roundUp(from, 8);
        offset + REC_HEADER_SIZE + CRC_SIZE <= limit;
        offset += 8) {
      if (segment.get(INT, offset) == REC_MAGIC && scan(offset, limit, null).records() > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Offset just past the last non-zero byte at or after {@code validEnd}, or {@code validEnd} if
   * only zeros follow it.
   */
  long dataEnd(final long validEnd) {
    long i = sizeAtOpen - 1;
    while (i >= validEnd) {
      if ((i & 7) == 7 && i - 7 >= validEnd && segment.get(LONG, i - 7) == 0) {
        i -= 8;
      } else if (segment.get(ValueLayout.JAVA_BYTE, i) == 0) {
        i--;
      } else {
        return i + 1;
      }
    }
    return validEnd;
  }

  /**
   * Declares {@code validEnd} the end of the log and wipes the bytes up to {@code dataEnd} that
   * follow it, so the "zero past the end" invariant holds again.
   *
   * @return the number of bytes discarded, 0 if the tail was already clean
   */
  long recoverTail(final long validEnd, final long dataEnd) {
    end = validEnd;
    forcedUpTo = validEnd;
    final long torn = dataEnd - validEnd;
    if (torn > 0) {
      final MemorySegment tail = segment.asSlice(validEnd, torn);
      tail.fill((byte) 0);
      tail.force();
    }
    return torn;
  }

  /**
   * Appends a record and returns the offset of its payload. The record is not durable until {@link
   * #force()}.
   */
  long append(final byte[] codeHash, final byte[] code) {
    final long start = end;
    final long total = recordSize(code.length);
    ensureMapped(start + total);

    final long payloadOffset = start + REC_HEADER_SIZE;
    segment.set(INT, start + 4, code.length);
    MemorySegment.copy(codeHash, 0, segment, ValueLayout.JAVA_BYTE, start + 8, codeHash.length);
    MemorySegment.copy(code, 0, segment, ValueLayout.JAVA_BYTE, payloadOffset, code.length);
    final CRC32C crc = new CRC32C();
    crc.update(codeHash);
    crc.update(code);
    segment.set(INT, payloadOffset + code.length, (int) crc.getValue());
    // The magic goes last: a writer killed mid-record leaves bytes that do not scan as a record.
    segment.set(INT, start, REC_MAGIC);

    end = start + total;
    return payloadOffset;
  }

  private void ensureMapped(final long required) {
    if (required <= segment.byteSize()) {
      return;
    }
    try {
      map(roundUp(required, growStep));
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to grow " + path, e);
    }
  }

  /** Zero-copy, read-only view of a payload. Valid until the store is closed. */
  MemorySegment payload(final long payloadOffset) {
    final long length =
        Integer.toUnsignedLong(segment.get(INT, payloadOffset - REC_HEADER_SIZE + 4));
    return segment.asSlice(payloadOffset, length).asReadOnly();
  }

  /** The code hash recorded with the payload at {@code payloadOffset}. */
  byte[] hashAt(final long payloadOffset) {
    return segment
        .asSlice(payloadOffset - CodeStore.HASH_SIZE, CodeStore.HASH_SIZE)
        .toArray(ValueLayout.JAVA_BYTE);
  }

  /** Makes every record appended so far durable. */
  void force() {
    if (end > forcedUpTo) {
      segment.asSlice(forcedUpTo, end - forcedUpTo).force();
      forcedUpTo = end;
    }
  }

  /** Pre-faults the log into memory. */
  void load() {
    segment.asSlice(0, end).load();
  }

  /** Releases the log without touching the file; for an open that failed part-way. */
  void abandon() throws IOException {
    arenas.forEach(Arena::close);
    arenas.clear();
    channel.close();
  }

  /** Forces the log and truncates the file back to the exact log length. */
  @Override
  public void close() throws IOException {
    try {
      force();
      arenas.forEach(Arena::close);
      arenas.clear();
      channel.truncate(end);
      channel.force(true);
    } finally {
      channel.close();
    }
  }

  static long recordSize(final long payloadLength) {
    return roundUp(REC_HEADER_SIZE + payloadLength + CRC_SIZE, 8);
  }

  static long roundUp(final long value, final long multiple) {
    return Math.max(multiple, (value + multiple - 1) / multiple * multiple);
  }

  private static int crc(final MemorySegment segment, final long offset, final long length) {
    final CRC32C crc = new CRC32C();
    for (long done = 0; done < length; done += CRC_CHUNK) {
      crc.update(segment.asSlice(offset + done, Math.min(CRC_CHUNK, length - done)).asByteBuffer());
    }
    return (int) crc.getValue();
  }
}
