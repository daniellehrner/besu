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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

class WarmStorageTableTest {

  private static final Address A = Address.fromHexString("0x0a");
  private static final Address B = Address.fromHexString("0x0b");
  private static final Bytes32 SLOT_1 = Bytes32.leftPad(Bytes.of(1));
  private static final Bytes32 SLOT_2 = Bytes32.leftPad(Bytes.of(2));

  @Test
  void firstTouchIsColdAndLaterTouchesAreWarm() {
    final WarmStorageTable table = new WarmStorageTable();
    assertThat(table.warmUp(A, SLOT_1)).isFalse();
    assertThat(table.warmUp(A, SLOT_1)).isTrue();
    assertThat(table.contains(A, SLOT_1)).isTrue();
    assertThat(table.size()).isEqualTo(1);
  }

  @Test
  void slotsAreKeyedByAddressAndSlot() {
    final WarmStorageTable table = new WarmStorageTable();
    table.warmUp(A, SLOT_1);
    assertThat(table.contains(B, SLOT_1)).isFalse();
    assertThat(table.contains(A, SLOT_2)).isFalse();
    assertThat(table.warmUp(B, SLOT_1)).isFalse();
    assertThat(table.warmUp(A, SLOT_2)).isFalse();
    assertThat(table.size()).isEqualTo(3);
  }

  @Test
  void equalKeysBuiltSeparatelyMatch() {
    final WarmStorageTable table = new WarmStorageTable();
    table.warmUp(Address.fromHexString("0x0a"), Bytes32.leftPad(Bytes.of(1)));
    assertThat(table.contains(Address.fromHexString("0x0a"), Bytes32.leftPad(Bytes.of(1))))
        .isTrue();
  }

  @Test
  void undoForgetsSlotsWarmedAfterTheMark() {
    final WarmStorageTable table = new WarmStorageTable();
    table.warmUp(A, SLOT_1);
    final long mark = table.mark();
    table.warmUp(A, SLOT_2);
    table.warmUp(B, SLOT_1);
    table.undo(mark);
    assertThat(table.contains(A, SLOT_1)).isTrue();
    assertThat(table.contains(A, SLOT_2)).isFalse();
    assertThat(table.contains(B, SLOT_1)).isFalse();
    assertThat(table.size()).isEqualTo(1);
    assertThat(table.warmUp(A, SLOT_2)).isFalse();
  }

  @Test
  void undoOfAnOlderMarkLeavesNothingWarmedSince() {
    final WarmStorageTable table = new WarmStorageTable();
    final long mark = table.mark();
    for (int i = 0; i < 500; i++) {
      table.warmUp(A, Bytes32.leftPad(Bytes.ofUnsignedInt(i)));
    }
    table.undo(mark);
    assertThat(table.isEmpty()).isTrue();
    assertThat(table.lastUpdate()).isZero();
    for (int i = 0; i < 500; i++) {
      assertThat(table.contains(A, Bytes32.leftPad(Bytes.ofUnsignedInt(i)))).isFalse();
    }
  }

  @Test
  void growsPastTheInitialCapacityWithoutLosingEntries() {
    final WarmStorageTable table = new WarmStorageTable();
    final Random random = new Random(7);
    final List<Bytes32> slots = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      final Bytes32 slot = Bytes32.random(random);
      slots.add(slot);
      assertThat(table.warmUp(A, slot)).isFalse();
    }
    assertThat(table.size()).isEqualTo(10_000);
    for (final Bytes32 slot : slots) {
      assertThat(table.contains(A, slot)).isTrue();
    }
  }

  @Test
  void forEachVisitsEveryWarmSlotOnce() {
    final WarmStorageTable table = new WarmStorageTable();
    table.warmUp(A, SLOT_1);
    table.warmUp(A, SLOT_2);
    table.warmUp(B, SLOT_1);
    final List<Map.Entry<Address, Bytes32>> seen = new ArrayList<>();
    table.forEach((address, slot) -> seen.add(Map.entry(address, slot)));
    assertThat(seen)
        .containsExactlyInAnyOrder(
            Map.entry(A, SLOT_1), Map.entry(A, SLOT_2), Map.entry(B, SLOT_1));
  }

  @Test
  void randomWarmUpsAndRevertsAgreeWithAReferenceSet() {
    final WarmStorageTable table = new WarmStorageTable();
    final Random random = new Random(11);
    final Address[] addresses = new Address[4];
    for (int i = 0; i < addresses.length; i++) {
      addresses[i] = Address.wrap(Bytes.random(20, random));
    }
    final Bytes32[] slots = new Bytes32[300];
    for (int i = 0; i < slots.length; i++) {
      slots[i] = Bytes32.random(random);
    }

    record Key(Address address, Bytes32 slot) {}
    final Set<Key> reference = new HashSet<>();
    final List<Set<Key>> snapshots = new ArrayList<>();
    final List<Long> marks = new ArrayList<>();

    for (int step = 0; step < 20_000; step++) {
      final int action = random.nextInt(100);
      if (action < 4) {
        marks.add(table.mark());
        snapshots.add(new HashSet<>(reference));
      } else if (action < 6 && !marks.isEmpty()) {
        final int depth = random.nextInt(marks.size());
        table.undo(marks.get(depth));
        reference.clear();
        reference.addAll(snapshots.get(depth));
        marks.subList(depth, marks.size()).clear();
        snapshots.subList(depth, snapshots.size()).clear();
      } else {
        final Key key =
            new Key(
                addresses[random.nextInt(addresses.length)], slots[random.nextInt(slots.length)]);
        assertThat(table.warmUp(key.address(), key.slot())).isEqualTo(!reference.add(key));
      }
      assertThat(table.size()).isEqualTo(reference.size());
    }
    for (final Key key : reference) {
      assertThat(table.contains(key.address(), key.slot())).isTrue();
    }
  }

  @Test
  void stackWordsAndBytesNameTheSameSlot() {
    final WarmStorageTable table = new WarmStorageTable();
    table.warmUp(A, SLOT_2);
    assertThat(warmUp(table, A, SLOT_2)).isNotNegative();
    assertThat(warmUp(table, A, SLOT_1)).isNegative();
    assertThat(table.contains(A, SLOT_1)).isTrue();
  }

  @Test
  void keepsTheValueTheTransactionRead() {
    final WarmStorageTable table = new WarmStorageTable();
    final int entry = ~warmUp(table, A, SLOT_1);
    final long[] stack = new long[8];
    assertThat(table.copyCurrentValue(entry, stack, 4)).isFalse();

    fill(table, entry, UInt256.valueOf(42));

    assertThat(table.currentValue(entry)).isEqualTo(UInt256.valueOf(42));
    assertThat(table.copyCurrentValue(warmUp(table, A, SLOT_1), stack, 4)).isTrue();
    assertThat(stack).containsExactly(0, 0, 0, 0, 0, 0, 0, 42);
  }

  @Test
  void rollbackRestoresTheValueAWriteReplaced() {
    final WarmStorageTable table = new WarmStorageTable();
    final int entry = ~warmUp(table, A, SLOT_1);
    fill(table, entry, UInt256.valueOf(1));
    final long mark = table.mark();

    write(table, warmUp(table, A, SLOT_1), UInt256.valueOf(2));
    write(table, warmUp(table, A, SLOT_1), UInt256.valueOf(3));
    assertThat(table.currentValue(warmUp(table, A, SLOT_1))).isEqualTo(UInt256.valueOf(3));

    table.undo(mark);
    final int restored = warmUp(table, A, SLOT_1);
    assertThat(table.currentValue(restored)).isEqualTo(UInt256.valueOf(1));
    final long[] stack = new long[4];
    table.copyCurrentValue(restored, stack, 0);
    assertThat(stack).containsExactly(0, 0, 0, 1);
  }

  @Test
  void rollbackOfAWriteToASlotOfUnknownValueForgetsIt() {
    final WarmStorageTable table = new WarmStorageTable();
    table.warmUp(A, SLOT_1);
    final long mark = table.mark();
    write(table, warmUp(table, A, SLOT_1), UInt256.valueOf(5));
    table.undo(mark);
    assertThat(table.contains(A, SLOT_1)).isTrue();
    assertThat(table.currentValue(warmUp(table, A, SLOT_1))).isNull();
  }

  @Test
  void rollbackForgetsSlotsWarmedSinceWithTheirValues() {
    final WarmStorageTable table = new WarmStorageTable();
    final long mark = table.mark();
    fill(table, ~warmUp(table, A, SLOT_1), UInt256.valueOf(9));
    table.undo(mark);
    final int entry = warmUp(table, A, SLOT_1);
    assertThat(entry).isNegative();
    assertThat(table.currentValue(~entry)).isNull();
  }

  @Test
  void clearForgetsTheValuesOfOneAccountAndARollbackForgetsThemAgain() {
    final WarmStorageTable table = new WarmStorageTable();
    fill(table, ~warmUp(table, A, SLOT_1), UInt256.valueOf(1));
    table.fillOriginalValue(warmUp(table, A, SLOT_1), UInt256.valueOf(1));
    fill(table, ~warmUp(table, B, SLOT_1), UInt256.valueOf(2));
    final long mark = table.mark();

    table.clearValues(A);
    assertThat(table.currentValue(warmUp(table, A, SLOT_1))).isNull();
    assertThat(table.originalValue(warmUp(table, A, SLOT_1))).isNull();
    assertThat(table.currentValue(warmUp(table, B, SLOT_1))).isEqualTo(UInt256.valueOf(2));

    // read after the clear, which the rollback undoes
    fill(table, warmUp(table, A, SLOT_1), UInt256.ZERO);
    table.undo(mark);
    assertThat(table.currentValue(warmUp(table, A, SLOT_1))).isNull();
    assertThat(table.currentValue(warmUp(table, B, SLOT_1))).isEqualTo(UInt256.valueOf(2));
  }

  @Test
  void growingKeepsTheValues() {
    final WarmStorageTable table = new WarmStorageTable();
    for (int i = 0; i < 1_000; i++) {
      fill(table, ~warmUp(table, A, Bytes32.leftPad(Bytes.ofUnsignedInt(i))), UInt256.valueOf(i));
    }
    for (int i = 0; i < 1_000; i++) {
      assertThat(table.currentValue(warmUp(table, A, Bytes32.leftPad(Bytes.ofUnsignedInt(i)))))
          .isEqualTo(UInt256.valueOf(i));
    }
  }

  @Test
  void knownValuesMatchTheStorageThroughRandomReadsWritesClearsAndRollbacks() {
    final WarmStorageTable table = new WarmStorageTable();
    final Random random = new Random(13);
    final Address[] addresses = new Address[3];
    for (int i = 0; i < addresses.length; i++) {
      addresses[i] = Address.wrap(Bytes.random(20, random));
    }
    final Bytes32[] slots = new Bytes32[40];
    for (int i = 0; i < slots.length; i++) {
      slots[i] = Bytes32.random(random);
    }

    record Key(Address address, Bytes32 slot) {}
    // what the world state holds, and the warm slots, at each mark
    Map<Key, UInt256> storage = new HashMap<>();
    Set<Key> warm = new HashSet<>();
    final List<Map<Key, UInt256>> storageSnapshots = new ArrayList<>();
    final List<Set<Key>> warmSnapshots = new ArrayList<>();
    final List<Long> marks = new ArrayList<>();

    for (int step = 0; step < 50_000; step++) {
      final int action = random.nextInt(100);
      final Key key =
          new Key(addresses[random.nextInt(addresses.length)], slots[random.nextInt(slots.length)]);
      if (action < 4) {
        marks.add(table.mark());
        storageSnapshots.add(new HashMap<>(storage));
        warmSnapshots.add(new HashSet<>(warm));
      } else if (action < 7 && !marks.isEmpty()) {
        final int depth = random.nextInt(marks.size());
        table.undo(marks.get(depth));
        storage = storageSnapshots.get(depth);
        warm = warmSnapshots.get(depth);
        marks.subList(depth, marks.size()).clear();
        storageSnapshots.subList(depth, storageSnapshots.size()).clear();
        warmSnapshots.subList(depth, warmSnapshots.size()).clear();
      } else if (action < 8) {
        table.clearValues(key.address());
        storage.keySet().removeIf(k -> k.address().equals(key.address()));
      } else if (action < 50) {
        // SSTORE
        final int found = warmUp(table, key.address(), key.slot());
        assertThat(found >= 0).isEqualTo(!warm.add(key));
        final UInt256 value = UInt256.valueOf(random.nextInt(4));
        write(table, found >= 0 ? found : ~found, value);
        storage.put(key, value);
      } else {
        // SLOAD
        final int found = warmUp(table, key.address(), key.slot());
        assertThat(found >= 0).isEqualTo(!warm.add(key));
        final int entry = found >= 0 ? found : ~found;
        final UInt256 actual = storage.getOrDefault(key, UInt256.ZERO);
        final UInt256 known = table.currentValue(entry);
        if (known == null) {
          fill(table, entry, actual);
        } else {
          assertThat(known).isEqualTo(actual);
        }
      }
      assertThat(table.size()).isEqualTo(warm.size());
    }
    for (final Key key : warm) {
      final UInt256 known = table.currentValue(warmUp(table, key.address(), key.slot()));
      if (known != null) {
        assertThat(known).isEqualTo(storage.getOrDefault(key, UInt256.ZERO));
      }
    }
  }

  private static int warmUp(
      final WarmStorageTable table, final Address address, final Bytes32 slot) {
    return table.warmUp(
        address, slot.getLong(0), slot.getLong(8), slot.getLong(16), slot.getLong(24));
  }

  private static void fill(final WarmStorageTable table, final int entry, final UInt256 value) {
    table.fillCurrentValue(
        entry, value, value.getLong(0), value.getLong(8), value.getLong(16), value.getLong(24));
  }

  private static void write(final WarmStorageTable table, final int entry, final UInt256 value) {
    table.writeCurrentValue(
        entry, value, value.getLong(0), value.getLong(8), value.getLong(16), value.getLong(24));
  }
}
