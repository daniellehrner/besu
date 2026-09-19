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

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeStoreTest {

  private static final CodeStoreOptions SMALL =
      CodeStoreOptions.defaults()
          .withLogGrowStep(64 * 1024)
          .withInitialIndexCapacity(16)
          .withPreload(false);

  @TempDir Path dir;

  static byte[] bytes(final MemorySegment segment) {
    return segment.toArray(ValueLayout.JAVA_BYTE);
  }

  @Test
  void roundTripsAcrossReopen() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      for (int i = 0; i < 500; i++) {
        store.put(TestData.hash(1, i), TestData.code(1, i));
      }
      store.sync();
      for (int i = 0; i < 500; i++) {
        assertThat(bytes(store.get(TestData.hash(1, i)).orElseThrow()))
            .isEqualTo(TestData.code(1, i));
      }
    }
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThat(store.entries()).isEqualTo(500);
      assertThat(store.truncatedBytesOnOpen()).isZero();
      assertThat(store.recoveredRecordsOnOpen()).isZero();
      assertThat(store.verify()).isEqualTo(500);
      for (int i = 0; i < 500; i++) {
        assertThat(bytes(store.get(TestData.hash(1, i)).orElseThrow()))
            .isEqualTo(TestData.code(1, i));
      }
      assertThat(store.get(TestData.hash(1, 500))).isEmpty();
      assertThat(store.contains(TestData.hash(1, 499))).isTrue();
      assertThat(store.contains(TestData.hash(1, 500))).isFalse();
    }
  }

  @Test
  void cleanCloseTruncatesTheLogToItsExactLength() throws IOException {
    final long logBytes;
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      store.put(TestData.hash(2, 0), new byte[100]);
      logBytes = store.logBytes();
    }
    assertThat(Files.size(dir.resolve(CodeLog.FILE_NAME))).isEqualTo(logBytes);
    assertThat(logBytes % 8).isZero();
  }

  @Test
  void storesEmptyCode() throws IOException {
    final byte[] hash = TestData.hash(3, 0);
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      store.put(hash, new byte[0]);
      assertThat(store.get(hash)).hasValueSatisfying(s -> assertThat(s.byteSize()).isZero());
    }
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThat(store.get(hash)).hasValueSatisfying(s -> assertThat(s.byteSize()).isZero());
    }
  }

  @Test
  void enforcesNoSizeCap() throws IOException {
    final byte[] big = new byte[5 * 1024 * 1024 + 3];
    new java.util.Random(4).nextBytes(big);
    final byte[] hash = TestData.hash(4, 0);
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      store.put(hash, big);
    }
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThat(bytes(store.get(hash).orElseThrow())).isEqualTo(big);
    }
  }

  @Test
  void firstPutForAHashWins() throws IOException {
    final byte[] hash = TestData.hash(5, 0);
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      store.put(hash, new byte[] {1});
      store.put(hash, new byte[] {2});
      store.sync();
      store.put(hash, new byte[] {3});
      assertThat(bytes(store.get(hash).orElseThrow())).containsExactly(1);
      assertThat(store.entries()).isEqualTo(1);
      assertThat(store.verify()).isEqualTo(1);
    }
  }

  @Test
  void unsyncedPutsAreReadable() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      store.put(TestData.hash(6, 0), TestData.code(6, 0));
      assertThat(bytes(store.get(TestData.hash(6, 0)).orElseThrow()))
          .isEqualTo(TestData.code(6, 0));
      assertThat(store.entries()).isEqualTo(1);
    }
  }

  @Test
  void viewsSurviveLogAndIndexGrowth() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      store.put(TestData.hash(7, 0), TestData.code(7, 0));
      final MemorySegment early = store.get(TestData.hash(7, 0)).orElseThrow();
      for (int i = 1; i < 2_000; i++) {
        store.put(TestData.hash(7, i), TestData.code(7, i));
        if (i % 97 == 0) {
          store.sync();
        }
      }
      assertThat(store.logBytes()).isGreaterThan(10 * 64 * 1024);
      assertThat(bytes(early)).isEqualTo(TestData.code(7, 0));
      assertThat(early.isReadOnly()).isTrue();
    }
  }

  @Test
  void viewsAreInvalidatedByClose() throws IOException {
    final MemorySegment view;
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      store.put(TestData.hash(8, 0), new byte[] {1, 2, 3});
      view = store.get(TestData.hash(8, 0)).orElseThrow();
    }
    assertThatThrownBy(() -> view.get(ValueLayout.JAVA_BYTE, 0))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void forEachVisitsSyncedAndPendingEntries() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      for (int i = 0; i < 50; i++) {
        store.put(TestData.hash(9, i), TestData.code(9, i));
        if (i == 30) {
          store.sync();
        }
      }
      final Set<String> seen = new HashSet<>();
      store.forEach((hash, code) -> seen.add(java.util.HexFormat.of().formatHex(hash)));
      assertThat(seen).hasSize(50);
    }
  }

  @Test
  void reportsProbes() throws IOException {
    final AtomicInteger lookups = new AtomicInteger();
    try (CodeStore store =
        CodeStore.open(dir, SMALL.withProbeListener(probes -> lookups.incrementAndGet()))) {
      store.put(TestData.hash(10, 0), new byte[1]);
      store.sync();
      store.get(TestData.hash(10, 0));
      assertThat(lookups.get()).isPositive();
    }
  }

  @Test
  void rejectsBadHashes() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThatThrownBy(() -> store.put(new byte[32], new byte[1]))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> store.get(new byte[31]))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesASecondOpen() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThatThrownBy(() -> CodeStore.open(dir, SMALL)).isInstanceOf(RuntimeException.class);
      store.put(TestData.hash(11, 0), new byte[1]);
    }
  }

  @Test
  void refusesUseAfterClose() throws IOException {
    final CodeStore store = CodeStore.open(dir, SMALL);
    store.close();
    store.close();
    assertThatThrownBy(() -> store.get(TestData.hash(12, 0)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesAStoreWithDifferentCodeKeying() throws IOException {
    CodeStore.open(dir, SMALL).close();
    final CodeStoreOptions accountKeyed =
        new CodeStoreOptions(64 * 1024, 16, false, "x", "account-hash", ProbeListener.NONE);
    assertThatThrownBy(() -> CodeStore.open(dir, accountKeyed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("code keying");
  }

  @Test
  void writesAManifest() throws IOException {
    CodeStore.open(dir, SMALL.withSourceBesuVersion("26.9.0-test")).close();
    final Manifest manifest = Manifest.parse(Files.readString(dir.resolve(Manifest.FILE_NAME)));
    assertThat(manifest.formatVersion()).isEqualTo(1);
    assertThat(manifest.sourceBesuVersion()).isEqualTo("26.9.0-test");
    assertThat(manifest.codeKeying()).isEqualTo(CodeStoreOptions.CODE_HASH_KEYING);
  }

  @Test
  void streamsEntriesInInsertionOrderLazily() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      for (int i = 0; i < 300; i++) {
        store.put(TestData.hash(14, i), TestData.code(14, i));
        if (i == 150) {
          store.sync();
        }
      }
      final List<Map.Entry<byte[], byte[]>> entries = store.stream().toList();
      assertThat(entries).hasSize(300);
      for (int i = 0; i < 300; i++) {
        assertThat(entries.get(i).getKey()).isEqualTo(TestData.hash(14, i));
        assertThat(entries.get(i).getValue()).isEqualTo(TestData.code(14, i));
      }
      assertThat(store.stream().limit(1).findFirst().orElseThrow().getKey())
          .isEqualTo(TestData.hash(14, 0));
    }
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThat(store.stream()).hasSize(300);
    }
  }

  @Test
  void streamOfAnEmptyStoreIsEmpty() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThat(store.stream()).isEmpty();
    }
  }

  @Test
  void putAllAndSyncStoresABatchDurably() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      final List<Map.Entry<byte[], byte[]>> batch = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        batch.add(Map.entry(TestData.hash(15, i), TestData.code(15, i)));
      }
      batch.add(Map.entry(TestData.hash(15, 3), new byte[] {9}));
      store.putAllAndSync(batch);
      assertThat(store.entries()).isEqualTo(100);
      assertThat(store.verify()).isEqualTo(100);
      assertThat(bytes(store.get(TestData.hash(15, 3)).orElseThrow()))
          .isEqualTo(TestData.code(15, 3));
    }
  }

  @Test
  void clearRemovesEverythingDurably() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      for (int i = 0; i < 200; i++) {
        store.put(TestData.hash(16, i), TestData.code(16, i));
      }
      store.sync();
      store.put(TestData.hash(16, 200), TestData.code(16, 200));
      final MemorySegment view = store.get(TestData.hash(16, 0)).orElseThrow();

      store.clear();

      assertThat(store.entries()).isZero();
      assertThat(store.logBytes()).isEqualTo(CodeLog.HEADER_SIZE);
      assertThat(store.contains(TestData.hash(16, 0))).isFalse();
      assertThat(store.contains(TestData.hash(16, 200))).isFalse();
      assertThatThrownBy(() -> view.get(ValueLayout.JAVA_BYTE, 0))
          .isInstanceOf(IllegalStateException.class);

      store.put(TestData.hash(16, 7), TestData.code(16, 7));
    }
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      assertThat(store.entries()).isEqualTo(1);
      assertThat(store.verify()).isEqualTo(1);
      assertThat(bytes(store.get(TestData.hash(16, 7)).orElseThrow()))
          .isEqualTo(TestData.code(16, 7));
    }
  }

  @Test
  void aStreamFailsIfTheStoreIsClearedUnderIt() throws IOException {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      for (int i = 0; i < 10; i++) {
        store.put(TestData.hash(17, i), TestData.code(17, i));
      }
      final java.util.Iterator<Map.Entry<byte[], byte[]>> entries = store.stream().iterator();
      entries.next();
      store.clear();
      assertThatThrownBy(entries::next)
          .isInstanceOf(java.util.ConcurrentModificationException.class);
    }
  }

  @Test
  void concurrentReadersSeeConsistentData() throws Exception {
    try (CodeStore store = CodeStore.open(dir, SMALL)) {
      final AtomicInteger written = new AtomicInteger();
      final List<Thread> readers = new ArrayList<>();
      final List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
      for (int t = 0; t < 4; t++) {
        final int id = t;
        readers.add(
            Thread.ofPlatform()
                .start(
                    () -> {
                      final java.util.SplittableRandom rng = new java.util.SplittableRandom(id);
                      try {
                        while (written.get() < 3_000) {
                          final int max = written.get();
                          if (max == 0) {
                            continue;
                          }
                          final int i = rng.nextInt(max);
                          assertThat(bytes(store.get(TestData.hash(13, i)).orElseThrow()))
                              .isEqualTo(TestData.code(13, i));
                        }
                      } catch (final Throwable e) {
                        failures.add(e);
                      }
                    }));
      }
      for (int i = 0; i < 3_000; i++) {
        store.put(TestData.hash(13, i), TestData.code(13, i));
        written.set(i + 1);
        if (i % 50 == 0) {
          store.sync();
        }
      }
      for (final Thread reader : readers) {
        reader.join();
      }
      assertThat(failures).isEmpty();
    }
  }
}
