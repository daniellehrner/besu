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
package org.hyperledger.besu.evm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class WarmAddressSetTest {

  private static final Address A = Address.fromHexString("0x0a");
  private static final Address B = Address.fromHexString("0x0b");

  @Test
  void firstTouchIsColdAndLaterTouchesAreWarm() {
    final WarmAddressSet set = new WarmAddressSet();
    assertThat(set.warmUp(A)).isFalse();
    assertThat(set.warmUp(A)).isTrue();
    assertThat(set.contains(A)).isTrue();
    assertThat(set.contains(B)).isFalse();
    assertThat(set.size()).isEqualTo(1);
  }

  @Test
  void equalAddressesBuiltSeparatelyMatch() {
    final WarmAddressSet set = new WarmAddressSet();
    set.warmUp(Address.fromHexString("0x0a"));
    assertThat(set.contains(Address.fromHexString("0x0a"))).isTrue();
  }

  @Test
  void undoForgetsAddressesWarmedAfterTheMark() {
    final WarmAddressSet set = new WarmAddressSet();
    set.warmUp(A);
    final long mark = set.mark();
    set.warmUp(B);
    set.undo(mark);
    assertThat(set.contains(A)).isTrue();
    assertThat(set.contains(B)).isFalse();
    assertThat(set.size()).isEqualTo(1);
    assertThat(set.warmUp(B)).isFalse();
  }

  @Test
  void undoOfAnOlderMarkLeavesNothingWarmedSince() {
    final WarmAddressSet set = new WarmAddressSet();
    final long mark = set.mark();
    for (int i = 0; i < 500; i++) {
      set.warmUp(Address.fromHexString(Integer.toHexString(i + 1)));
    }
    set.undo(mark);
    assertThat(set.isEmpty()).isTrue();
    assertThat(set.lastUpdate()).isZero();
  }

  @Test
  void growsPastTheInitialCapacityWithoutLosingEntries() {
    final WarmAddressSet set = new WarmAddressSet();
    final Random random = new Random(7);
    final List<Address> addresses = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      final Address address = Address.wrap(Bytes.random(20, random));
      addresses.add(address);
      assertThat(set.warmUp(address)).isFalse();
    }
    assertThat(set.size()).isEqualTo(10_000);
    for (final Address address : addresses) {
      assertThat(set.contains(address)).isTrue();
    }
  }

  @Test
  void forEachVisitsEveryWarmAddressOnce() {
    final WarmAddressSet set = new WarmAddressSet();
    set.warmUp(A);
    set.warmUp(B);
    final List<Address> seen = new ArrayList<>();
    set.forEach(seen::add);
    assertThat(seen).containsExactlyInAnyOrder(A, B);
  }

  @Test
  void randomWarmUpsAndRevertsAgreeWithAReferenceSet() {
    final WarmAddressSet set = new WarmAddressSet();
    final Random random = new Random(11);
    final Address[] addresses = new Address[200];
    for (int i = 0; i < addresses.length; i++) {
      addresses[i] = Address.wrap(Bytes.random(20, random));
    }
    final Set<Address> reference = new HashSet<>();
    final List<Set<Address>> snapshots = new ArrayList<>();
    final List<Long> marks = new ArrayList<>();

    for (int step = 0; step < 20_000; step++) {
      final int action = random.nextInt(100);
      if (action < 4) {
        marks.add(set.mark());
        snapshots.add(new HashSet<>(reference));
      } else if (action < 6 && !marks.isEmpty()) {
        final int depth = random.nextInt(marks.size());
        set.undo(marks.get(depth));
        reference.clear();
        reference.addAll(snapshots.get(depth));
        marks.subList(depth, marks.size()).clear();
        snapshots.subList(depth, snapshots.size()).clear();
      } else {
        final Address address = addresses[random.nextInt(addresses.length)];
        assertThat(set.warmUp(address)).isEqualTo(!reference.add(address));
      }
      assertThat(set.size()).isEqualTo(reference.size());
    }
    for (final Address address : reference) {
      assertThat(set.contains(address)).isTrue();
    }
  }
}
