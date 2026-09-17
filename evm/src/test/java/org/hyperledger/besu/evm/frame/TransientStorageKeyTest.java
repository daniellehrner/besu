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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

class TransientStorageKeyTest {

  private static final Address ADDRESS_A = Address.fromHexString("0x01");
  private static final Address ADDRESS_B = Address.fromHexString("0x02");

  @Test
  void keysWithSameContentAreEqualRegardlessOfSlotType() {
    final Bytes32 slot =
        Bytes32.fromHexString("0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    final TransientStorageKey bytesKey = new TransientStorageKey(ADDRESS_A, slot);
    final TransientStorageKey uintKey = new TransientStorageKey(ADDRESS_A, UInt256.fromBytes(slot));

    assertThat(uintKey).isEqualTo(bytesKey);
    assertThat(bytesKey).isEqualTo(uintKey);
    assertThat(uintKey.hashCode()).isEqualTo(bytesKey.hashCode());
    assertThat(uintKey.compareTo(bytesKey)).isZero();
  }

  @Test
  void keysDifferingInAddressOrSlotAreNotEqual() {
    final TransientStorageKey key = new TransientStorageKey(ADDRESS_A, UInt256.ONE);

    assertThat(new TransientStorageKey(ADDRESS_B, UInt256.ONE)).isNotEqualTo(key);
    assertThat(new TransientStorageKey(ADDRESS_A, UInt256.valueOf(2))).isNotEqualTo(key);
    assertThat(new TransientStorageKey(ADDRESS_B, UInt256.ONE).compareTo(key)).isPositive();
    assertThat(new TransientStorageKey(ADDRESS_A, UInt256.valueOf(2)).compareTo(key)).isPositive();
  }
}
