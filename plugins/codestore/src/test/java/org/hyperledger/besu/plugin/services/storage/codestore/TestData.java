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

import java.util.SplittableRandom;

/** Deterministic records: record {@code i} of a given seed is always the same hash and code. */
final class TestData {
  static final int MAX_CODE_SIZE = 24_576;

  private TestData() {}

  private static SplittableRandom rng(final long seed, final long i) {
    return new SplittableRandom(seed * 0x9E3779B97F4A7C15L + i);
  }

  static byte[] hash(final long seed, final long i) {
    final byte[] hash = new byte[CodeStore.HASH_SIZE];
    rng(seed, i).nextBytes(hash);
    hash[31] |= 1;
    return hash;
  }

  /**
   * Sizes cover 0..24,576 with a skew towards small contracts, which keeps a million records at a
   * few GiB; one record in 5,000 is oversize to show the store enforces no cap.
   */
  static byte[] code(final long seed, final long i) {
    final SplittableRandom rng = rng(~seed, i);
    final int size;
    final int kind = rng.nextInt(5_000);
    if (kind == 0) {
      size = MAX_CODE_SIZE + 1 + rng.nextInt(200_000);
    } else if (kind < 100) {
      size = 0;
    } else if (kind < 200) {
      size = MAX_CODE_SIZE - rng.nextInt(8);
    } else if (kind < 1_500) {
      size = rng.nextInt(MAX_CODE_SIZE + 1);
    } else {
      size = rng.nextInt(4_096);
    }
    final byte[] code = new byte[size];
    rng.nextBytes(code);
    return code;
  }
}
