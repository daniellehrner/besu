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
package org.hyperledger.besu.ethereum.eth.sync.snapsync;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.TrieGenerator;
import org.hyperledger.besu.ethereum.proof.WorldStateProofProvider;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.RangeManager;
import org.hyperledger.besu.ethereum.trie.RangeStorageEntriesCollector;
import org.hyperledger.besu.ethereum.trie.TrieIterator;
import org.hyperledger.besu.ethereum.trie.forest.storage.ForestWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A response is stored with the trie that validated it. That has to store exactly what building the
 * trie again from the keys and proofs of the response stores.
 */
public class StackTrieRangeTrieTest {

  private static final int ACCOUNT_COUNT = 240;

  private MerkleTrie<Bytes, Bytes> accountTrie;
  private Hash rootHash;
  private WorldStateProofProvider proofProvider;
  private List<Bytes32> accountHashes;

  /**
   * Creates the trie of the test.
   *
   * @param spreadKeys whether the keys of the accounts are spread over the key space, as hashes
   *     are. Otherwise they only differ in their last byte, which puts an extension node above all
   *     of them.
   */
  private void createTrie(final boolean spreadKeys) {
    final WorldStateStorageCoordinator worldStateStorageCoordinator =
        new WorldStateStorageCoordinator(
            new ForestWorldStateKeyValueStorage(new InMemoryKeyValueStorage()));
    accountTrie =
        spreadKeys
            ? TrieGenerator.generateTrie(
                worldStateStorageCoordinator,
                IntStream.range(0, ACCOUNT_COUNT)
                    .mapToObj(account -> Hash.hash(Bytes.ofUnsignedInt(account)))
                    .toList())
            : TrieGenerator.generateTrie(worldStateStorageCoordinator, ACCOUNT_COUNT);
    rootHash = Hash.wrap(accountTrie.getRootHash());
    proofProvider = new WorldStateProofProvider(worldStateStorageCoordinator);
    accountHashes = new ArrayList<>(keysFrom(RangeManager.MIN_RANGE, Integer.MAX_VALUE).keySet());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldStoreTheSameForARangeFromTheStartOfTheTrie(final boolean spreadKeys) {
    createTrie(spreadKeys);
    assertStoresTheSameWithTheTrieOfTheValidation(RangeManager.MIN_RANGE, 40);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldStoreTheSameForARangeThatStartsWithAKeyOfTheTrie(final boolean spreadKeys) {
    createTrie(spreadKeys);
    for (int first = 3; first < ACCOUNT_COUNT - 30; first += 17) {
      assertStoresTheSameWithTheTrieOfTheValidation(accountHashes.get(first), 25);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldStoreTheSameForARangeThatStartsBetweenTwoKeysOfTheTrie(
      final boolean spreadKeys) {
    createTrie(spreadKeys);
    // The proof of a start that is not in the trie ends at a node next to it, which can be the leaf
    // of the key before the range.
    for (int before = 0; before < ACCOUNT_COUNT - 30; before += 7) {
      final Bytes32 start = UInt256.fromBytes(accountHashes.get(before)).add(1);
      assertStoresTheSameWithTheTrieOfTheValidation(start, 25);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldStoreTheSameForARangeOfASingleKey(final boolean spreadKeys) {
    createTrie(spreadKeys);
    for (int first = 0; first < ACCOUNT_COUNT; first += 23) {
      assertStoresTheSameWithTheTrieOfTheValidation(accountHashes.get(first), 1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldStoreTheSameForARangeUpToTheEndOfTheTrie(final boolean spreadKeys) {
    createTrie(spreadKeys);
    assertStoresTheSameWithTheTrieOfTheValidation(
        accountHashes.get(ACCOUNT_COUNT - 12), Integer.MAX_VALUE);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldStoreTheSameForTheWholeTrieWithoutAProof(final boolean spreadKeys) {
    createTrie(spreadKeys);
    final NavigableMap<Bytes32, Bytes> accounts =
        keysFrom(RangeManager.MIN_RANGE, Integer.MAX_VALUE);
    final List<Bytes> noProofs = List.of();
    final WorldStateProofProvider.RangeProofValidation validation =
        proofProvider.validateRangeProof(
            RangeManager.MIN_RANGE,
            RangeManager.MAX_RANGE,
            Bytes32.wrap(rootHash.getBytes()),
            noProofs,
            accounts);

    assertThat(validation.isValid()).isTrue();
    assertThat(validation.rangeTrie()).isPresent();
    final List<String> stored =
        stored(RangeManager.MIN_RANGE, noProofs, accounts, validation.rangeTrie());
    assertThat(stored)
        .isEqualTo(stored(RangeManager.MIN_RANGE, noProofs, accounts, Optional.empty()));
    // all of the trie, with its root
    assertThat(stored).anyMatch(node -> node.startsWith("node 0x "));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldNotStoreTheLeafBeforeTheRangeThatItsProofEndsAt(final boolean spreadKeys) {
    createTrie(spreadKeys);
    for (int before = 0; before < ACCOUNT_COUNT - 30; before += 7) {
      final Bytes32 keyBefore = accountHashes.get(before);
      final Bytes32 start = UInt256.fromBytes(keyBefore).add(1);
      final NavigableMap<Bytes32, Bytes> accounts = keysFrom(start, 25);
      final List<Bytes> proofs = proofs(start, accounts.lastKey());
      final WorldStateProofProvider.RangeProofValidation validation =
          proofProvider.validateRangeProof(
              start, RangeManager.MAX_RANGE, Bytes32.wrap(rootHash.getBytes()), proofs, accounts);

      final List<String> stored = stored(start, proofs, accounts, validation.rangeTrie());

      assertThat(stored).noneMatch(entry -> entry.equals("flat " + keyBefore));
      assertThat(stored)
          .noneMatch(
              entry ->
                  entry.startsWith("node ")
                      && entry.endsWith(
                          " " + accountTrie.get(keyBefore).orElseThrow().toHexString()));
    }
  }

  private void assertStoresTheSameWithTheTrieOfTheValidation(
      final Bytes32 start, final int accountCount) {
    final NavigableMap<Bytes32, Bytes> accounts = keysFrom(start, accountCount);
    final List<Bytes> proofs = proofs(start, accounts.lastKey());
    final WorldStateProofProvider.RangeProofValidation validation =
        proofProvider.validateRangeProof(
            start, RangeManager.MAX_RANGE, Bytes32.wrap(rootHash.getBytes()), proofs, accounts);

    assertThat(validation.isValid()).isTrue();
    assertThat(validation.rangeTrie()).isPresent();
    final List<String> stored = stored(start, proofs, accounts, validation.rangeTrie());
    assertThat(stored)
        .describedAs("range of %s keys from %s", accounts.size(), start)
        .isEqualTo(stored(start, proofs, accounts, Optional.empty()));
    assertThat(stored)
        .filteredOn(entry -> entry.startsWith("flat "))
        .hasSameSizeAs(accounts.keySet());
    assertThat(stored).anyMatch(entry -> entry.startsWith("node "));
  }

  /** What the stack trie hands to the storage for a response, in the order it does. */
  private List<String> stored(
      final Bytes32 start,
      final List<Bytes> proofs,
      final NavigableMap<Bytes32, Bytes> accounts,
      final Optional<MerkleTrie<Bytes, Bytes>> rangeTrie) {
    final List<String> stored = new ArrayList<>();
    final StackTrie stackTrie = new StackTrie(rootHash, start);
    stackTrie.addElement(start, proofs, accounts, rangeTrie);
    stackTrie.commit(
        (key, value) -> stored.add("flat " + key),
        (location, hash, value) -> stored.add("node " + location + " " + hash + " " + value),
        false);
    return stored;
  }

  private List<Bytes> proofs(final Bytes32 start, final Bytes32 lastKey) {
    final List<Bytes> proofs =
        new ArrayList<>(proofProvider.getAccountProofRelatedNodes(rootHash, start));
    proofs.addAll(proofProvider.getAccountProofRelatedNodes(rootHash, lastKey));
    return proofs;
  }

  private NavigableMap<Bytes32, Bytes> keysFrom(final Bytes32 start, final int maxCount) {
    final RangeStorageEntriesCollector collector =
        RangeStorageEntriesCollector.createCollector(
            start, RangeManager.MAX_RANGE, maxCount, Integer.MAX_VALUE);
    final TrieIterator<Bytes> visitor = RangeStorageEntriesCollector.createVisitor(collector);
    return (TreeMap<Bytes32, Bytes>)
        accountTrie.entriesFrom(
            root -> RangeStorageEntriesCollector.collectEntries(collector, visitor, root, start));
  }
}
