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
import static org.hyperledger.besu.plugin.services.storage.codestore.CodeStoreTest.bytes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Random put/get pairs must round-trip byte-identically: each put is followed by a get of a random
 * earlier record, syncs and reopens are interleaved, and a full sweep runs after the final reopen.
 */
class PropertyTest {

  @TempDir Path dir;

  @Test
  void twentyThousandPairs() throws IOException {
    run(20_000, 3);
  }

  @Test
  @Tag("slow")
  void fullSize() throws IOException {
    run(Long.getLong("codestore.property.pairs", 1_000_000), 4);
  }

  private void run(final long pairs, final int reopens) throws IOException {
    final long seed = System.nanoTime();
    System.out.printf("PropertyTest: %,d pairs, seed %d%n", pairs, seed);
    final SplittableRandom rng = new SplittableRandom(seed);
    final CodeStoreOptions options =
        CodeStoreOptions.defaults().withInitialIndexCapacity(1024).withPreload(false);
    final long reopenEvery = pairs / (reopens + 1) + 1;

    long maxSize = 0;
    long zeroSized = 0;
    CodeStore store = CodeStore.open(dir, options);
    try {
      for (long i = 0; i < pairs; i++) {
        final byte[] code = TestData.code(seed, i);
        maxSize = Math.max(maxSize, code.length);
        zeroSized += code.length == 0 ? 1 : 0;
        store.put(TestData.hash(seed, i), code);

        final long j = rng.nextLong(i + 1);
        assertThat(bytes(store.get(TestData.hash(seed, j)).orElseThrow()))
            .as("record %d after put %d", j, i)
            .isEqualTo(TestData.code(seed, j));

        if (rng.nextInt(2_000) == 0) {
          store.sync();
        }
        if (rng.nextInt(50) == 0) {
          // A repeated put must neither fail nor replace anything.
          store.put(TestData.hash(seed, j), new byte[] {42});
        }
        if ((i + 1) % reopenEvery == 0) {
          store.close();
          store = CodeStore.open(dir, options);
        }
      }
      store.close();

      store = CodeStore.open(dir, options);
      assertThat(store.entries()).isEqualTo(pairs);
      assertThat(store.verify()).isEqualTo(pairs);
      for (long i = 0; i < pairs; i++) {
        assertThat(bytes(store.get(TestData.hash(seed, i)).orElseThrow()))
            .as("record %d in the final sweep", i)
            .isEqualTo(TestData.code(seed, i));
      }
      assertThat(store.contains(TestData.hash(seed, pairs))).isFalse();
      System.out.printf(
          "PropertyTest: %,d pairs ok, log %,d bytes, largest record %,d bytes, %,d empty%n",
          pairs, store.logBytes(), maxSize, zeroSized);
    } finally {
      store.close();
    }
  }
}
