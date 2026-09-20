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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hyperledger.besu.plugin.services.storage.codestore.CodeStoreTest.bytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery from crash images. An image is a copy of the store's files taken while the store is
 * still open, which is what a killed process leaves behind: a log file longer than the log and an
 * index that lags it.
 */
class RecoveryTest {

  private static final CodeStoreOptions SMALL =
      CodeStoreOptions.defaults()
          .withLogGrowStep(64 * 1024)
          .withInitialIndexCapacity(16)
          .withPreload(false);

  private static final int SYNCED = 200;
  private static final int UNSYNCED = 40;

  @TempDir Path live;
  @TempDir Path image;

  private long syncedLogBytes;
  private long logBytes;

  @BeforeEach
  void takeCrashImage() throws IOException {
    try (CodeStore store = CodeStore.open(live, SMALL)) {
      for (int i = 0; i < SYNCED; i++) {
        store.put(TestData.hash(1, i), TestData.code(1, i));
      }
      store.sync();
      syncedLogBytes = store.logBytes();
      for (int i = SYNCED; i < SYNCED + UNSYNCED; i++) {
        store.put(TestData.hash(1, i), TestData.code(1, i));
      }
      logBytes = store.logBytes();
      for (final String name : new String[] {CodeLog.FILE_NAME, CodeIndex.FILE_NAME, "MANIFEST"}) {
        Files.copy(live.resolve(name), image.resolve(name));
      }
    }
  }

  private void assertIntact(final CodeStore store, final int records) {
    assertThat(store.entries()).isEqualTo(records);
    assertThat(store.verify()).isEqualTo(records);
    for (int i = 0; i < records; i++) {
      assertThat(bytes(store.get(TestData.hash(1, i)).orElseThrow()))
          .isEqualTo(TestData.code(1, i));
    }
  }

  private void writeToLog(final long offset, final byte[] data) throws IOException {
    try (FileChannel ch =
        FileChannel.open(image.resolve(CodeLog.FILE_NAME), StandardOpenOption.WRITE)) {
      ch.write(ByteBuffer.wrap(data), offset);
    }
  }

  private static byte[] record(final byte[] hash, final byte[] code, final boolean validCrc) {
    final ByteBuffer buf = ByteBuffer.allocate((int) CodeLog.recordSize(code.length));
    final CRC32C crc = new CRC32C();
    crc.update(hash);
    crc.update(code);
    buf.putInt(CodeLog.REC_MAGIC).putInt(code.length).put(hash).put(code);
    buf.putInt((int) crc.getValue() ^ (validCrc ? 0 : 1));
    return buf.array();
  }

  @Test
  void reindexesRecordsBeyondTheWatermark() throws IOException {
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertThat(store.recoveredRecordsOnOpen()).isEqualTo(UNSYNCED);
      assertThat(store.truncatedBytesOnOpen()).isZero();
      assertThat(store.logBytes()).isEqualTo(logBytes);
      assertIntact(store, SYNCED + UNSYNCED);
    }
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertThat(store.recoveredRecordsOnOpen()).isZero();
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void truncatesARecordWithABadCrc() throws IOException {
    final byte[] torn = record(TestData.hash(2, 0), new byte[300], false);
    writeToLog(logBytes, torn);
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      // Header, zero payload and the CRC that ends the record; padding is not counted.
      assertThat(store.truncatedBytesOnOpen()).isEqualTo(CodeLog.REC_HEADER_SIZE + 300 + 4);
      assertThat(store.logBytes()).isEqualTo(logBytes);
      assertThat(store.contains(TestData.hash(2, 0))).isFalse();
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void truncatesARecordWhoseMagicWasNeverWritten() throws IOException {
    final byte[] torn = record(TestData.hash(2, 0), TestData.code(1, 7), true);
    torn[0] = torn[1] = torn[2] = torn[3] = 0;
    writeToLog(logBytes, torn);
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertThat(store.truncatedBytesOnOpen()).isPositive();
      assertThat(store.contains(TestData.hash(2, 0))).isFalse();
      assertIntact(store, SYNCED + UNSYNCED);
      // The wiped tail must not confuse the next append.
      store.put(TestData.hash(1, SYNCED + UNSYNCED), TestData.code(1, SYNCED + UNSYNCED));
    }
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertIntact(store, SYNCED + UNSYNCED + 1);
    }
  }

  @Test
  void truncatesARecordThatClaimsToRunPastTheFile() throws IOException {
    final ByteBuffer header = ByteBuffer.allocate(48);
    header.putInt(CodeLog.REC_MAGIC).putInt(0x7fff_fff0);
    writeToLog(logBytes, header.array());
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertThat(store.truncatedBytesOnOpen()).isEqualTo(8);
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void dropsEverythingAfterATornRecordBeyondTheWatermark() throws IOException {
    // Flip a byte in the first unsynced record: it and the valid records after it were never
    // acknowledged, so they are cut rather than reported as corruption.
    writeToLog(syncedLogBytes + CodeLog.REC_HEADER_SIZE - 1, new byte[] {(byte) 0xA5});
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertThat(store.logBytes()).isEqualTo(syncedLogBytes);
      // Up to 7 bytes of zero padding after the last record are not counted as discarded.
      assertThat(store.truncatedBytesOnOpen())
          .isBetween(logBytes - syncedLogBytes - 7, logBytes - syncedLogBytes);
      assertIntact(store, SYNCED);
    }
  }

  @Test
  void rebuildsAMissingIndex() throws IOException {
    Files.delete(image.resolve(CodeIndex.FILE_NAME));
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertThat(store.recoveredRecordsOnOpen()).isEqualTo(SYNCED + UNSYNCED);
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void rebuildsAnIndexWithABadMagic() throws IOException {
    try (FileChannel ch =
        FileChannel.open(image.resolve(CodeIndex.FILE_NAME), StandardOpenOption.WRITE)) {
      ch.write(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 0);
    }
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void ignoresALeftoverIndexBuild() throws IOException {
    Files.write(image.resolve(CodeIndex.FILE_NAME + ".new"), new byte[1234]);
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void failsLoudlyOnCorruptionInTheMiddleWhenRebuilding() throws IOException {
    Files.delete(image.resolve(CodeIndex.FILE_NAME));
    writeToLog(CodeLog.HEADER_SIZE + CodeLog.REC_HEADER_SIZE - 1, new byte[] {(byte) 0xA5});
    assertThatThrownBy(() -> CodeStore.open(image, SMALL))
        .isInstanceOf(CorruptCodeStoreException.class)
        .hasMessageContaining("re-migrated");
    // A failed open must leave the evidence alone.
    assertThat(Files.size(image.resolve(CodeLog.FILE_NAME))).isGreaterThanOrEqualTo(logBytes);
  }

  @Test
  void verifyFailsLoudlyOnCorruptionBelowTheWatermark() throws IOException {
    writeToLog(CodeLog.HEADER_SIZE + CodeLog.REC_HEADER_SIZE - 1, new byte[] {(byte) 0xA5});
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertThatThrownBy(store::verify).isInstanceOf(CorruptCodeStoreException.class);
    }
  }

  @Test
  void failsLoudlyWhenTheLogIsShorterThanTheIndexClaims() throws IOException {
    try (FileChannel ch =
        FileChannel.open(image.resolve(CodeLog.FILE_NAME), StandardOpenOption.WRITE)) {
      ch.truncate(syncedLogBytes - 8);
    }
    assertThatThrownBy(() -> CodeStore.open(image, SMALL))
        .isInstanceOf(CorruptCodeStoreException.class);
  }

  @Test
  void aClearInterruptedAfterDeletingTheIndexDidNotHappen() throws IOException {
    // clear() deletes the index before it replaces the log; dying in between is a missing index.
    Files.delete(image.resolve(CodeIndex.FILE_NAME));
    Files.write(image.resolve(CodeLog.FILE_NAME + ".new"), new byte[17]);
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  /** Offset in {@code code.idx} of the first empty slot. */
  private long firstEmptySlot() throws IOException {
    final byte[] idx = Files.readAllBytes(image.resolve(CodeIndex.FILE_NAME));
    for (int base = CodeIndex.HEADER_SIZE; base < idx.length; base += CodeIndex.SLOT_SIZE) {
      boolean empty = true;
      for (int i = 0; i < CodeStore.HASH_SIZE; i++) {
        empty &= idx[base + i] == 0;
      }
      if (empty) {
        return base;
      }
    }
    throw new IllegalStateException("no empty slot");
  }

  private void writeToIndex(final long offset, final byte[] data) throws IOException {
    try (FileChannel ch =
        FileChannel.open(image.resolve(CodeIndex.FILE_NAME), StandardOpenOption.WRITE)) {
      ch.write(ByteBuffer.wrap(data), offset);
    }
  }

  // A slot can straddle two pages, and after a power failure only one of them may have reached
  // the disk. Either half alone is an occupied slot that matches no record.

  @Test
  void repairsASlotWhoseSecondHalfWasLost() throws IOException {
    final byte[] firstHalf = new byte[16];
    java.util.Arrays.fill(firstHalf, (byte) 0x5A);
    writeToIndex(firstEmptySlot(), firstHalf);
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void repairsASlotWhoseFirstHalfWasLost() throws IOException {
    final ByteBuffer secondHalf = ByteBuffer.allocate(CodeIndex.SLOT_SIZE - 16);
    secondHalf.put(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    secondHalf.putLong(syncedLogBytes + CodeLog.REC_HEADER_SIZE);
    writeToIndex(firstEmptySlot() + 16, secondHalf.array());
    try (CodeStore store = CodeStore.open(image, SMALL)) {
      assertIntact(store, SYNCED + UNSYNCED);
    }
  }

  @Test
  void failsLoudlyOnABadLogMagic() throws IOException {
    writeToLog(0, new byte[] {9, 9, 9, 9});
    assertThatThrownBy(() -> CodeStore.open(image, SMALL))
        .isInstanceOf(CorruptCodeStoreException.class);
  }
}
