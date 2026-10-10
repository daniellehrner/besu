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
package org.hyperledger.besu.ethereum.mainnet.staterootcommitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.patricia.StoredMerklePatriciaTrie;
import org.hyperledger.besu.plugin.services.worldstate.StateRootComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

class StateRootComputationsTest {

  private record StoredNode(Bytes location, Bytes32 hash, Bytes value) {}

  @Test
  void trieNodesCollectedAheadOfApplyingAreTheNodesApplyingStores() {
    final List<StoredNode> storedWhenApplied = new ArrayList<>();
    final StateRootComputation applied = deferredCommitOf(populatedTrie(), storedWhenApplied);
    applied.applyTo(mock(BonsaiWorldStateKeyValueStorage.Updater.class));

    final List<StoredNode> storedWhenCollected = new ArrayList<>();
    final StateRootComputation collected = deferredCommitOf(populatedTrie(), storedWhenCollected);
    StateRootComputations.collectDeferredTrieNodes(collected).join();
    assertThat(storedWhenCollected).isEmpty();
    collected.applyTo(mock(BonsaiWorldStateKeyValueStorage.Updater.class));

    assertThat(storedWhenApplied).isNotEmpty();
    assertThat(storedWhenCollected).containsExactlyElementsOf(storedWhenApplied);
  }

  @Test
  void collectingWithoutDeferredTrieCommitsCompletesAtOnce() {
    final StateRootComputation computation =
        StateRootComputations.withAllWrites(Hash.EMPTY_TRIE_HASH, List.of(updater -> {}));

    assertThat(StateRootComputations.collectDeferredTrieNodes(computation)).isCompleted();
  }

  private static MerkleTrie<Bytes, Bytes> populatedTrie() {
    final MerkleTrie<Bytes, Bytes> trie =
        new StoredMerklePatriciaTrie<>(
            (location, hash) -> Optional.empty(), Function.identity(), Function.identity());
    for (int i = 0; i < 64; i++) {
      trie.put(Hash.hash(Bytes.ofUnsignedInt(i)).getBytes(), Bytes32.leftPad(Bytes.of(i + 1)));
    }
    // the root is hashed before the writes are applied, as in a frozen root computation
    trie.getRootHash();
    return trie;
  }

  private static StateRootComputation deferredCommitOf(
      final MerkleTrie<Bytes, Bytes> trie, final List<StoredNode> stored) {
    final ConcurrentLinkedQueue<StateRootComputations.UpdaterWrite> writes =
        new ConcurrentLinkedQueue<>();
    new PersistingSink(writes, true)
        .commitTrie(
            trie,
            (location, hash, value) ->
                updater -> stored.add(new StoredNode(location, hash, value)));
    return StateRootComputations.withAllWrites(
        Hash.wrap(trie.getRootHash()), new ArrayList<>(writes));
  }
}
