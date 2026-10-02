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
package org.hyperledger.besu.ethereum.eth.messages.snap;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

public class RangeEntriesTest {

  @Test
  public void shouldBuildTheMapOfEntriesInKeyOrder() {
    final TreeMap<Bytes32, Bytes> expected = new TreeMap<>();
    for (int i = 0; i < 5_000; i++) {
      expected.put(Bytes32.wrap(Hash.hash(Bytes.ofUnsignedInt(i)).getBytes()), Bytes.of(i % 100));
    }
    final RangeEntries entries = new RangeEntries();
    expected.forEach(entries::add);

    final NavigableMap<Bytes32, Bytes> map = entries.toMap();

    assertThat(map).isEqualTo(expected);
    assertThat(map.keySet()).containsExactlyElementsOf(expected.keySet());
    assertThat(map.firstKey()).isEqualTo(expected.firstKey());
    assertThat(map.lastKey()).isEqualTo(expected.lastKey());
    // a map that finds every key, and the keys around a key
    final List<Bytes32> keys = new ArrayList<>(expected.keySet());
    for (int i = 0; i < keys.size(); i++) {
      assertThat(map.get(keys.get(i))).isEqualTo(expected.get(keys.get(i)));
      assertThat(map.higherKey(keys.get(i)))
          .isEqualTo(i + 1 < keys.size() ? keys.get(i + 1) : null);
    }
    assertThat(map.headMap(keys.get(100), false)).hasSize(100);
    // and that is a map like any other afterwards
    map.put(Bytes32.ZERO, Bytes.EMPTY);
    assertThat(map.firstKey()).isEqualTo(Bytes32.ZERO);
    assertThat(map).hasSize(expected.size() + 1);
  }

  @Test
  public void shouldSortEntriesThatAreNotInKeyOrder() {
    final RangeEntries entries = new RangeEntries();
    entries.add(key(3), Bytes.of(3));
    entries.add(key(1), Bytes.of(1));
    entries.add(key(2), Bytes.of(2));

    final NavigableMap<Bytes32, Bytes> map = entries.toMap();

    assertThat(map.keySet()).containsExactly(key(1), key(2), key(3));
    assertThat(map.get(key(1))).isEqualTo(Bytes.of(1));
  }

  @Test
  public void shouldKeepTheLastOfTheEntriesWithTheSameKey() {
    final RangeEntries entries = new RangeEntries();
    entries.add(key(1), Bytes.of(1));
    entries.add(key(2), Bytes.of(2));
    entries.add(key(2), Bytes.of(22));
    entries.add(key(3), Bytes.of(3));

    final NavigableMap<Bytes32, Bytes> map = entries.toMap();

    assertThat(map.keySet()).containsExactly(key(1), key(2), key(3));
    assertThat(map.get(key(2))).isEqualTo(Bytes.of(22));
  }

  @Test
  public void shouldCompareKeysAsUnsignedNumbers() {
    // 0x80.. is above 0x7f.., which a comparison of signed bytes gets wrong
    final Bytes32 low = Bytes32.rightPad(Bytes.of(0x7f));
    final Bytes32 high = Bytes32.rightPad(Bytes.of(0x80));
    final RangeEntries ascending = new RangeEntries();
    ascending.add(low, Bytes.of(1));
    ascending.add(high, Bytes.of(2));
    final RangeEntries descending = new RangeEntries();
    descending.add(high, Bytes.of(2));
    descending.add(low, Bytes.of(1));

    assertThat(ascending.toMap().keySet()).containsExactly(low, high);
    assertThat(descending.toMap().keySet()).containsExactly(low, high);
    assertThat(ascending.toMap().get(high)).isEqualTo(Bytes.of(2));
  }

  @Test
  public void shouldBuildAnEmptyMapWithoutEntries() {
    final NavigableMap<Bytes32, Bytes> map = new RangeEntries().toMap();

    assertThat(map).isEmpty();
    map.put(key(1), Bytes.of(1));
    assertThat(map).hasSize(1);
  }

  private static Bytes32 key(final int lastByte) {
    return Bytes32.leftPad(Bytes.of(lastByte));
  }
}
