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

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Collects the entries of a range response into the map the range is handed on in.
 *
 * <p>A peer sends the entries of a range in key order. Put into a {@link TreeMap} one by one, each
 * of them is compared with a dozen keys to find the place it is sent in already, and comparing two
 * hashes byte by byte is what decoding a response then spends most of its time on. So the order is
 * checked while the entries are collected, with one comparison per entry, and the map is built from
 * the sorted entries without any.
 *
 * <p>Entries that do not come in strictly ascending order are put into the map one by one, which
 * sorts them and keeps the last of the entries with the same key.
 */
final class RangeEntries {

  private final List<Bytes32> keys = new ArrayList<>();
  private final List<Bytes> values = new ArrayList<>();
  private byte[] lastKey;
  private boolean ascending = true;

  void add(final Bytes32 key, final Bytes value) {
    final byte[] keyBytes = key.toArrayUnsafe();
    // keys of the same length compare as the numbers they are
    if (lastKey != null && Arrays.compareUnsigned(lastKey, keyBytes) >= 0) {
      ascending = false;
    }
    lastKey = keyBytes;
    keys.add(key);
    values.add(value);
  }

  NavigableMap<Bytes32, Bytes> toMap() {
    if (ascending) {
      // a TreeMap copies a sorted map in the order of its entries
      return new TreeMap<>(new SortedEntries());
    }
    final TreeMap<Bytes32, Bytes> map = new TreeMap<>();
    for (int i = 0; i < keys.size(); i++) {
      map.put(keys.get(i), values.get(i));
    }
    return map;
  }

  /**
   * The collected entries as a sorted map, with what it takes to be copied. A SortedMap, because
   * that is what a TreeMap copies in order.
   */
  @SuppressWarnings("JdkObsolete")
  private final class SortedEntries extends AbstractMap<Bytes32, Bytes>
      implements SortedMap<Bytes32, Bytes> {

    @Override
    public Comparator<? super Bytes32> comparator() {
      // the natural ordering of the keys
      return null;
    }

    @Override
    public Set<Map.Entry<Bytes32, Bytes>> entrySet() {
      return new AbstractSet<>() {
        @Override
        public Iterator<Map.Entry<Bytes32, Bytes>> iterator() {
          return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
              return next < keys.size();
            }

            @Override
            public Map.Entry<Bytes32, Bytes> next() {
              final Map.Entry<Bytes32, Bytes> entry =
                  new SimpleImmutableEntry<>(keys.get(next), values.get(next));
              next++;
              return entry;
            }
          };
        }

        @Override
        public int size() {
          return keys.size();
        }
      };
    }

    @Override
    public Bytes32 firstKey() {
      return keys.getFirst();
    }

    @Override
    public Bytes32 lastKey() {
      return keys.getLast();
    }

    @Override
    public SortedMap<Bytes32, Bytes> subMap(final Bytes32 fromKey, final Bytes32 toKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SortedMap<Bytes32, Bytes> headMap(final Bytes32 toKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SortedMap<Bytes32, Bytes> tailMap(final Bytes32 fromKey) {
      throw new UnsupportedOperationException();
    }
  }
}
