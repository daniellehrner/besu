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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.trie.RangeManager.createPath;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.trie.patricia.BranchNode;
import org.hyperledger.besu.ethereum.trie.patricia.ExtensionNode;
import org.hyperledger.besu.ethereum.trie.patricia.StoredMerklePatriciaTrie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

/**
 * Which nodes of a trie the visitor stores for a range of keys, against the way the range of a
 * location was first checked: with the location copied into a path of the length of a key.
 */
public class SnapCommitVisitorRangeTest {

  private static final int KEY_COUNT = 400;

  @Test
  public void shouldStoreTheNodesOfARangeOfSpreadKeys() {
    final List<Bytes32> keys = new ArrayList<>();
    for (int i = 0; i < KEY_COUNT; i++) {
      keys.add(Bytes32.wrap(Hash.hash(Bytes.ofUnsignedInt(i)).getBytes()));
    }
    assertStoresTheSameAsTheReferenceForRangesOf(keys);
  }

  @Test
  public void shouldStoreTheNodesOfARangeOfKeysBelowExtensionNodes() {
    // keys that share most of their path, in groups that share even more of it
    final List<Bytes32> keys = new ArrayList<>();
    for (int i = 0; i < KEY_COUNT; i++) {
      keys.add(Bytes32.leftPad(Bytes.ofUnsignedInt(i * 4099L)));
      keys.add(Bytes32.rightPad(Bytes.ofUnsignedInt(0x7f000000L + i * 257L)));
    }
    assertStoresTheSameAsTheReferenceForRangesOf(keys);
  }

  private void assertStoresTheSameAsTheReferenceForRangesOf(final List<Bytes32> keys) {
    final List<Bytes32> sorted = new ArrayList<>(new TreeSet<>(keys));
    final List<Bytes32[]> ranges = new ArrayList<>();
    ranges.add(new Bytes32[] {RangeManager.MIN_RANGE, RangeManager.MAX_RANGE});
    ranges.add(new Bytes32[] {RangeManager.MIN_RANGE, sorted.get(sorted.size() / 2)});
    ranges.add(new Bytes32[] {sorted.get(sorted.size() / 2), RangeManager.MAX_RANGE});
    for (int first = 0; first + 40 < sorted.size(); first += 31) {
      // from a key to a key, from between two keys, and up to between two keys
      ranges.add(new Bytes32[] {sorted.get(first), sorted.get(first + 40)});
      ranges.add(new Bytes32[] {plusOne(sorted.get(first)), sorted.get(first + 40)});
      ranges.add(new Bytes32[] {sorted.get(first), plusOne(sorted.get(first + 7))});
      ranges.add(new Bytes32[] {sorted.get(first), sorted.get(first)});
    }

    for (final Bytes32[] range : ranges) {
      final List<String> stored =
          storedNodes(keys, updater -> visitor(updater, range[0], range[1]));
      final List<String> expected =
          storedNodes(keys, updater -> new ReferenceVisitor(updater, range[0], range[1]));

      assertThat(stored).describedAs("range %s to %s", range[0], range[1]).isEqualTo(expected);
    }
    // and something is stored at all
    assertThat(
            storedNodes(
                keys, updater -> visitor(updater, RangeManager.MIN_RANGE, RangeManager.MAX_RANGE)))
        .hasSizeGreaterThan(keys.size() / 2);
  }

  /** The visitor the way the snap sync uses it: a node that is not complete is not stored. */
  private static CommitVisitor<Bytes> visitor(
      final NodeUpdater updater, final Bytes32 startKeyHash, final Bytes32 endKeyHash) {
    return new SnapCommitVisitor<>(updater, startKeyHash, endKeyHash) {
      @Override
      public void maybeStoreNode(final Bytes location, final Node<Bytes> node) {
        if (!node.isHealNeeded()) {
          super.maybeStoreNode(location, node);
        }
      }
    };
  }

  /** The nodes a visitor stores of a trie with the given keys, all of whose nodes are new. */
  private static List<String> storedNodes(
      final List<Bytes32> keys, final Function<NodeUpdater, CommitVisitor<Bytes>> visitor) {
    final MerkleTrie<Bytes, Bytes> trie =
        new StoredMerklePatriciaTrie<>(
            (location, hash) -> Optional.empty(), Function.identity(), Function.identity());
    // values long enough for every leaf to be a node of its own
    keys.forEach(key -> trie.put(key, Bytes.concatenate(key, key)));
    final List<String> stored = new ArrayList<>();
    final NodeUpdater updater = (location, hash, value) -> stored.add(location + " " + hash);
    trie.commit(updater, visitor.apply(updater));
    return stored;
  }

  private static Bytes32 plusOne(final Bytes32 key) {
    return UInt256.fromBytes(key).add(1);
  }

  /** The visitor as it was before the range of a location was checked in place. */
  private static class ReferenceVisitor extends CommitVisitor<Bytes> {
    private final Bytes startKeyPath;
    private final Bytes endKeyPath;

    ReferenceVisitor(
        final NodeUpdater nodeUpdater, final Bytes32 startKeyHash, final Bytes32 endKeyHash) {
      super(nodeUpdater);
      this.startKeyPath = createPath(startKeyHash);
      this.endKeyPath = createPath(endKeyHash);
    }

    @Override
    public void visit(final Bytes location, final ExtensionNode<Bytes> extensionNode) {
      if (!extensionNode.isDirty()) {
        return;
      }
      final Node<Bytes> child = extensionNode.getChild();
      if (child.isDirty()) {
        child.accept(Bytes.concatenate(location, extensionNode.getPath()), this);
      }
      if (child.isHealNeeded()
          || !isInRange(Bytes.concatenate(location, extensionNode.getPath()))) {
        extensionNode.markHealNeeded();
      }
      maybeStoreNode(location, extensionNode);
    }

    @Override
    public void visit(final Bytes location, final BranchNode<Bytes> branchNode) {
      if (!branchNode.isDirty()) {
        return;
      }
      for (int i = 0; i < branchNode.maxChild(); ++i) {
        final Bytes index = Bytes.of(i);
        final Node<Bytes> child = branchNode.child((byte) i);
        if (child.isDirty()) {
          child.accept(Bytes.concatenate(location, index), this);
        }
        if (child.isHealNeeded() || !isInRange(Bytes.concatenate(location, index))) {
          branchNode.markHealNeeded();
        }
      }
      maybeStoreNode(location, branchNode);
    }

    @Override
    public void maybeStoreNode(final Bytes location, final Node<Bytes> node) {
      // as the snap sync uses the visitor: a node that is not complete is not stored
      if (!node.isHealNeeded()) {
        super.maybeStoreNode(location, node);
      }
    }

    private boolean isInRange(final Bytes location) {
      final MutableBytes path = MutableBytes.create(Bytes32.SIZE * 2);
      path.set(0, location);
      return Arrays.compare(path.toArrayUnsafe(), startKeyPath.toArrayUnsafe()) >= 0
          && Arrays.compare(path.toArrayUnsafe(), endKeyPath.toArrayUnsafe()) <= 0;
    }
  }
}
