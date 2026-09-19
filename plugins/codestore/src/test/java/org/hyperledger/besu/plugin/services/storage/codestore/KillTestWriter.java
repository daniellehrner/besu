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

import java.nio.file.Path;
import java.util.SplittableRandom;

/**
 * Child process of {@link KillTest}: writes records {@code start, start+1, ...} for ever and
 * reports each completed sync on stdout as {@code S <index of the last record it covers>}.
 */
public final class KillTestWriter {
  private KillTestWriter() {}

  static final CodeStoreOptions OPTIONS =
      CodeStoreOptions.defaults()
          .withLogGrowStep(8L << 20)
          .withInitialIndexCapacity(64)
          .withPreload(false);

  public static void main(final String[] args) throws Exception {
    final Path dir = Path.of(args[0]);
    final long seed = Long.parseLong(args[1]);
    final long start = Long.parseLong(args[2]);
    final SplittableRandom rng = new SplittableRandom(System.nanoTime());

    final CodeStore store = CodeStore.open(dir, OPTIONS);
    System.out.println("OPEN");
    long nextSync = start + rng.nextInt(1, 30);
    for (long i = start; ; i++) {
      store.put(TestData.hash(seed, i), TestData.code(seed, i));
      if (i >= nextSync) {
        store.sync();
        System.out.println("S " + i);
        nextSync = i + rng.nextInt(1, 30);
      }
    }
  }
}
