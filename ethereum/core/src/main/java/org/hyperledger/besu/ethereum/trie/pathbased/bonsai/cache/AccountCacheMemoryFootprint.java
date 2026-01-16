/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache;

import org.hyperledger.besu.datatypes.Hash;

import org.apache.tuweni.bytes.Bytes;

/** Memory footprint estimator for account cache entries. */
class AccountCacheMemoryFootprint {

  // Overhead for Caffeine entry wrapper, object headers, and references
  private static final int ENTRY_OVERHEAD = 80;

  /**
   * Estimates the memory footprint of a cached account entry.
   *
   * @param key the address hash key (32 bytes)
   * @param value the RLP-encoded account data
   * @return estimated memory usage in bytes
   */
  public static int estimate(final Hash key, final Bytes value) {
    // Memory breakdown:
    // - ENTRY_OVERHEAD: Caffeine entry wrapper, object headers, references (~80 bytes)
    // - key.size(): Hash key size (32 bytes for Keccak-256)
    // - value.size(): RLP-encoded account data (typically ~70-100 bytes)
    //   Account RLP format: [nonce, balance, storageRoot, codeHash]
    //   - nonce: variable (1-9 bytes RLP)
    //   - balance: variable (1-33 bytes RLP)
    //   - storageRoot: 32 bytes + RLP overhead
    //   - codeHash: 32 bytes + RLP overhead
    return ENTRY_OVERHEAD + key.size() + value.size();
  }
}
