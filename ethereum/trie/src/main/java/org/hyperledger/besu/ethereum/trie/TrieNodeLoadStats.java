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
package org.hyperledger.besu.ethereum.trie;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide counters for the cost of materialising trie nodes: RLP decoding and the keccak that
 * validates a node read from storage against its expected hash. Both happen below the layers that
 * have access to the metrics system, so they are accumulated here and published by whoever owns a
 * metrics system.
 */
public final class TrieNodeLoadStats {

  private static final LongAdder DECODE_COUNT = new LongAdder();
  private static final LongAdder DECODE_NANOS = new LongAdder();
  private static final LongAdder VALIDATION_HASH_COUNT = new LongAdder();
  private static final LongAdder VALIDATION_HASH_NANOS = new LongAdder();

  private TrieNodeLoadStats() {}

  public static void recordDecode(final long nanos) {
    DECODE_COUNT.increment();
    DECODE_NANOS.add(nanos);
  }

  public static void recordValidationHash(final long nanos) {
    VALIDATION_HASH_COUNT.increment();
    VALIDATION_HASH_NANOS.add(nanos);
  }

  public static long decodeCount() {
    return DECODE_COUNT.sum();
  }

  public static long decodeNanos() {
    return DECODE_NANOS.sum();
  }

  public static long validationHashCount() {
    return VALIDATION_HASH_COUNT.sum();
  }

  public static long validationHashNanos() {
    return VALIDATION_HASH_NANOS.sum();
  }
}
