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

import static org.hyperledger.besu.plugin.services.storage.codestore.CodeLog.INT;
import static org.hyperledger.besu.plugin.services.storage.codestore.CodeLog.LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The open-addressed hash index ({@code code.idx}): linear probing, power-of-two capacity, load
 * factor at most 0.5. All multibyte fields are big-endian.
 *
 * <p>The index is derived from the log and can always be rebuilt from it, so nothing here needs to
 * be crash-atomic beyond one rule: the header's {@code logLength} is only advanced, and forced,
 * after every slot for the records below it has been written.
 *
 * <p>Not thread-safe; {@link CodeStore} serialises writers and excludes them from readers.
 */
final class CodeIndex implements AutoCloseable {

  static final String FILE_NAME = "code.idx";
  static final int HEADER_SIZE = 64;
  static final int SLOT_SIZE = CodeStore.HASH_SIZE + 8;
  static final int MAGIC = 0x42435349; // "BCSI"
  static final int VERSION = 1;

  private static final long CAPACITY_OFFSET = 8;
  private static final long COUNT_OFFSET = 16;
  private static final long LOG_LENGTH_OFFSET = 24;

  /** Receives every occupied slot. */
  @FunctionalInterface
  interface SlotVisitor {
    void visit(long logOffset);
  }

  private final Path dir;
  private final FileChannel channel;
  private final Arena arena;
  private final MemorySegment segment;
  private final long capacity;
  private long count;
  private long logLength;

  private CodeIndex(
      final Path dir,
      final FileChannel channel,
      final Arena arena,
      final MemorySegment segment,
      final long capacity) {
    this.dir = dir;
    this.channel = channel;
    this.arena = arena;
    this.segment = segment;
    this.capacity = capacity;
    this.count = segment.get(LONG, COUNT_OFFSET);
    this.logLength = segment.get(LONG, LOG_LENGTH_OFFSET);
  }

  /** Opens {@code code.idx}, or returns empty if it is missing or not a usable index. */
  static Optional<CodeIndex> openExisting(final Path dir) throws IOException {
    final Path path = dir.resolve(FILE_NAME);
    if (Files.notExists(path)) {
      return Optional.empty();
    }
    final FileChannel channel =
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    final Arena arena = Arena.ofShared();
    try {
      final long size = channel.size();
      if (size < HEADER_SIZE + SLOT_SIZE) {
        release(arena, channel);
        return Optional.empty();
      }
      final MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
      final long capacity = segment.get(LONG, CAPACITY_OFFSET);
      final long count = segment.get(LONG, COUNT_OFFSET);
      final boolean usable =
          segment.get(INT, 0) == MAGIC
              && segment.get(INT, 4) == VERSION
              && capacity > 0
              && Long.bitCount(capacity) == 1
              && size == fileSize(capacity)
              && count >= 0
              && count <= capacity / 2
              && segment.get(LONG, LOG_LENGTH_OFFSET) >= CodeLog.HEADER_SIZE;
      if (!usable) {
        release(arena, channel);
        return Optional.empty();
      }
      return Optional.of(new CodeIndex(dir, channel, arena, segment, capacity));
    } catch (final Throwable t) {
      release(arena, channel);
      throw t;
    }
  }

  /** Replaces whatever {@code code.idx} exists with an empty index. */
  static CodeIndex create(final Path dir, final long capacity) throws IOException {
    return build(dir, capacity, null);
  }

  private static CodeIndex build(final Path dir, final long capacity, final CodeIndex source)
      throws IOException {
    if (capacity <= 0 || Long.bitCount(capacity) != 1) {
      throw new IllegalArgumentException("capacity must be a power of two: " + capacity);
    }
    final Path tmp = dir.resolve(FILE_NAME + ".new");
    Files.deleteIfExists(tmp);
    final FileChannel channel =
        FileChannel.open(
            tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
    final Arena arena = Arena.ofShared();
    try {
      final MemorySegment segment =
          channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize(capacity), arena);
      segment.set(INT, 0, MAGIC);
      segment.set(INT, 4, VERSION);
      segment.set(LONG, CAPACITY_OFFSET, capacity);
      segment.set(LONG, LOG_LENGTH_OFFSET, (long) CodeLog.HEADER_SIZE);
      final CodeIndex index = new CodeIndex(dir, channel, arena, segment, capacity);
      if (source != null) {
        source.copySlotsInto(index);
        index.logLength = source.logLength;
      }
      index.writeHeader();
      segment.force();
      Durable.rename(tmp, dir.resolve(FILE_NAME));
      return index;
    } catch (final Throwable t) {
      release(arena, channel);
      throw t;
    }
  }

  private void copySlotsInto(final CodeIndex target) {
    final byte[] hash = new byte[CodeStore.HASH_SIZE];
    for (long slot = 0; slot < capacity; slot++) {
      final long base = slotBase(slot);
      if (!isEmpty(base)) {
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, base, hash, 0, hash.length);
        target.insertIfAbsent(hash, segment.get(LONG, base + CodeStore.HASH_SIZE));
      }
    }
  }

  /**
   * Builds an index of twice the capacity holding the same entries and renames it over this one.
   * This index stays readable; the caller closes it once it has switched to the returned one.
   */
  CodeIndex grown() throws IOException {
    return build(dir, capacity * 2, this);
  }

  /** True if {@code extra} more entries would push the load factor above 0.5. */
  boolean needsGrowFor(final long extra) {
    return (count + extra) * 2 > capacity;
  }

  long count() {
    return count;
  }

  long capacity() {
    return capacity;
  }

  /** Log length up to which this index is known to be complete. */
  long logLength() {
    return logLength;
  }

  /**
   * Looks up a code hash.
   *
   * @return the payload offset in the log, or -1 if absent
   */
  long find(final byte[] codeHash, final ProbeListener probes) {
    final long mask = capacity - 1;
    final MemorySegment key = MemorySegment.ofArray(codeHash);
    long slot = homeSlot(codeHash) & mask;
    for (int probe = 1; ; probe++, slot = (slot + 1) & mask) {
      final long base = slotBase(slot);
      if (matches(base, key)) {
        probes.onProbes(probe);
        return segment.get(LONG, base + CodeStore.HASH_SIZE);
      }
      if (isEmpty(base)) {
        probes.onProbes(probe);
        return -1;
      }
    }
  }

  /**
   * Inserts unless the hash is already present; the first entry for a hash wins. The caller must
   * have made room with {@link #needsGrowFor}.
   *
   * @return -1 if inserted, otherwise the payload offset already recorded for the hash
   */
  long insertIfAbsent(final byte[] codeHash, final long logOffset) {
    if (needsGrowFor(1)) {
      throw new IllegalStateException("index is full, grow it first");
    }
    final long mask = capacity - 1;
    final MemorySegment key = MemorySegment.ofArray(codeHash);
    long slot = homeSlot(codeHash) & mask;
    while (true) {
      final long base = slotBase(slot);
      if (isEmpty(base)) {
        // Offset first: a slot only becomes visible to a scan once its hash is non-zero.
        segment.set(LONG, base + CodeStore.HASH_SIZE, logOffset);
        MemorySegment.copy(codeHash, 0, segment, ValueLayout.JAVA_BYTE, base, codeHash.length);
        count++;
        return -1;
      }
      if (matches(base, key)) {
        return segment.get(LONG, base + CodeStore.HASH_SIZE);
      }
      slot = (slot + 1) & mask;
    }
  }

  /**
   * Recomputes the entry count from the slots. An interrupted sync leaves slots written that the
   * header count does not include yet.
   */
  void recount() {
    final long[] occupied = new long[1];
    forEach(offset -> occupied[0]++);
    count = occupied[0];
  }

  /** Records that the index now covers the log up to {@code newLogLength}, durably. */
  void commit(final long newLogLength) {
    logLength = newLogLength;
    segment.force();
    writeHeader();
    segment.asSlice(0, HEADER_SIZE).force();
  }

  void forEach(final SlotVisitor visitor) {
    for (long slot = 0; slot < capacity; slot++) {
      final long base = slotBase(slot);
      if (!isEmpty(base)) {
        visitor.visit(segment.get(LONG, base + CodeStore.HASH_SIZE));
      }
    }
  }

  private void writeHeader() {
    segment.set(LONG, COUNT_OFFSET, count);
    segment.set(LONG, LOG_LENGTH_OFFSET, logLength);
  }

  // Keys are keccak outputs, so their leading bytes are already uniformly distributed.
  private static long homeSlot(final byte[] codeHash) {
    return MemorySegment.ofArray(codeHash).get(LONG, 0);
  }

  private static long slotBase(final long slot) {
    return HEADER_SIZE + slot * SLOT_SIZE;
  }

  private boolean isEmpty(final long base) {
    return segment.get(LONG, base) == 0
        && segment.get(LONG, base + 8) == 0
        && segment.get(LONG, base + 16) == 0
        && segment.get(LONG, base + 24) == 0;
  }

  private boolean matches(final long base, final MemorySegment key) {
    return MemorySegment.mismatch(
            segment, base, base + CodeStore.HASH_SIZE, key, 0, CodeStore.HASH_SIZE)
        == -1;
  }

  private static long fileSize(final long capacity) {
    return HEADER_SIZE + capacity * SLOT_SIZE;
  }

  private static void release(final Arena arena, final FileChannel channel) throws IOException {
    arena.close();
    channel.close();
  }

  @Override
  public void close() throws IOException {
    release(arena, channel);
  }
}
