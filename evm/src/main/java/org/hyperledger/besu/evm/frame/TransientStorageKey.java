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
package org.hyperledger.besu.evm.frame;

import org.hyperledger.besu.crypto.SecureRandomProvider;
import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Hash map key for a transient storage slot of an account.
 *
 * <p>Address and Bytes32 hash with a grindable base-31 hash, so the hash is seeded per process to
 * keep attackers from precomputing colliding slots. Implementing {@code Comparable} of this exact
 * type additionally lets HashMap treeify any colliding bucket, bounding lookups to O(log n).
 *
 * @param address the account owning the slot
 * @param slot the transient storage slot
 */
public record TransientStorageKey(Address address, Bytes32 slot)
    implements Comparable<TransientStorageKey> {

  private static final long SEED = SecureRandomProvider.publicSecureRandom().nextLong();

  @Override
  public int hashCode() {
    final Bytes a = address.getBytes();
    long h = SEED;
    h = mix(h ^ a.getLong(0));
    h = mix(h ^ a.getLong(8));
    h = mix(h ^ a.getInt(16));
    h = mix(h ^ slot.getLong(0));
    h = mix(h ^ slot.getLong(8));
    h = mix(h ^ slot.getLong(16));
    h = mix(h ^ slot.getLong(24));
    return (int) (h ^ (h >>> 32));
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof TransientStorageKey other
        && slot.equals(other.slot)
        && address.equals(other.address);
  }

  @Override
  public int compareTo(final TransientStorageKey other) {
    final int c = address.compareTo(other.address);
    return c != 0 ? c : slot.compareTo(other.slot);
  }

  private static long mix(final long value) {
    long h = value;
    h ^= h >>> 33;
    h *= 0xff51afd7ed558ccdL;
    h ^= h >>> 33;
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= h >>> 33;
    return h;
  }
}
