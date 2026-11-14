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
import static org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.WorldStateConfig.createStatefulConfigWithTrie;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.code.BonsaiCodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.trielog.NoOpTrieLogManager;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.preload.NoOpBonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.cache.NoOpBonsaiWorldStateCacheManager;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.units.bigints.UInt256;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A state root computed with storage tries precomputed from predicted slot values is the root the
 * block's own values give, whatever the predictions got wrong.
 */
class PrecomputedStorageTriesTest {

  private static final Address CONTRACT =
      Address.fromHexString("0x00000000000000000000000000000000000c0de1");
  private static final Address OTHER =
      Address.fromHexString("0x00000000000000000000000000000000000c0de2");
  private static final StorageSlotKey S1 = new StorageSlotKey(UInt256.valueOf(1));
  private static final StorageSlotKey S2 = new StorageSlotKey(UInt256.valueOf(2));
  private static final StorageSlotKey S3 = new StorageSlotKey(UInt256.valueOf(3));
  private static final StorageSlotKey S4 = new StorageSlotKey(UInt256.valueOf(4));

  private BonsaiWorldState worldState;

  @BeforeEach
  void setUp() {
    final BonsaiWorldStateKeyValueStorage storage =
        new BonsaiWorldStateKeyValueStorage(
            new InMemoryKeyValueStorageProvider(),
            new NoOpMetricsSystem(),
            DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);
    worldState =
        new BonsaiWorldState(
            storage,
            new NoOpBonsaiCachedMerkleTrieLoader(),
            new NoOpBonsaiWorldStateCacheManager(
                storage, EvmConfiguration.DEFAULT, new BonsaiCodeCache()),
            new NoOpTrieLogManager(),
            EvmConfiguration.DEFAULT,
            createStatefulConfigWithTrie(),
            new BonsaiCodeCache());
    // the parent state: S1=1, S2=2, S3=3 on the contract, S1=1 on the other
    final BonsaiWorldStateUpdateAccumulator parent =
        (BonsaiWorldStateUpdateAccumulator) worldState.updater();
    final MutableAccount contract = parent.createAccount(CONTRACT, 1L, Wei.ONE);
    contract.setStorageValue(S1.getSlotKey().orElseThrow(), UInt256.valueOf(1));
    contract.setStorageValue(S2.getSlotKey().orElseThrow(), UInt256.valueOf(2));
    contract.setStorageValue(S3.getSlotKey().orElseThrow(), UInt256.valueOf(3));
    parent
        .createAccount(OTHER, 1L, Wei.ONE)
        .setStorageValue(S1.getSlotKey().orElseThrow(), UInt256.valueOf(1));
    parent.commit();
    worldState.persist(null);
  }

  @AfterEach
  void tearDown() {
    worldState.close();
  }

  /** The block: S1=10, S2 removed, S3 written with its parent value, S4=40; the other's S1=11. */
  private BonsaiWorldStateUpdateAccumulator applyBlock() {
    final BonsaiWorldStateUpdateAccumulator block =
        (BonsaiWorldStateUpdateAccumulator) worldState.updater();
    final MutableAccount contract = block.getAccount(CONTRACT);
    contract.setStorageValue(S1.getSlotKey().orElseThrow(), UInt256.valueOf(10));
    contract.setStorageValue(S2.getSlotKey().orElseThrow(), UInt256.ZERO);
    contract.setStorageValue(S3.getSlotKey().orElseThrow(), UInt256.valueOf(3));
    contract.setStorageValue(S4.getSlotKey().orElseThrow(), UInt256.valueOf(40));
    block.getAccount(OTHER).setStorageValue(S1.getSlotKey().orElseThrow(), UInt256.valueOf(11));
    block.commit();
    return block;
  }

  private Hash expectedRoot() {
    return new DefaultStateRootCommitter().compute(worldState, null, applyBlock().copy()).root();
  }

  private Hash rootWith(
      final Map<Address, Map<StorageSlotKey, UInt256>> predicted, final int expectedTaken) {
    final PrecomputedStorageTries tries = new PrecomputedStorageTries(worldState);
    tries.start(predicted);
    final BonsaiWorldStateUpdateAccumulator block = applyBlock();
    // the block's computation takes over only finished tries
    Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> allDone(tries, predicted.size()));
    final Hash root =
        new DefaultStateRootCommitter(tries).compute(worldState, null, block.copy()).root();
    assertThat(tries.counters()[1]).as("tries taken").isEqualTo(expectedTaken);
    return root;
  }

  private static boolean allDone(final PrecomputedStorageTries tries, final int count) {
    return tries.doneForTest() >= count;
  }

  @Test
  void exactPredictionsGiveTheBlocksRoot() {
    final Hash expected = expectedRoot();
    worldState.updater().reset();

    assertThat(
            rootWith(
                Map.of(
                    CONTRACT,
                    Map.of(S1, UInt256.valueOf(10), S2, UInt256.ZERO, S4, UInt256.valueOf(40)),
                    OTHER,
                    Map.of(S1, UInt256.valueOf(11))),
                2))
        .isEqualTo(expected);
  }

  @Test
  void wrongValuesAreCorrected() {
    final Hash expected = expectedRoot();
    worldState.updater().reset();

    assertThat(
            rootWith(
                Map.of(
                    CONTRACT,
                    Map.of(
                        S1, UInt256.valueOf(99), S2, UInt256.valueOf(5), S4, UInt256.valueOf(40)),
                    OTHER,
                    Map.of(S1, UInt256.ZERO)),
                2))
        .isEqualTo(expected);
  }

  @Test
  void aSlotTheBlockWroteWithItsParentValueIsRestored() {
    final Hash expected = expectedRoot();
    worldState.updater().reset();

    assertThat(rootWith(Map.of(CONTRACT, Map.of(S3, UInt256.valueOf(77))), 1)).isEqualTo(expected);
  }

  @Test
  void slotsThePredictionMissedAreApplied() {
    final Hash expected = expectedRoot();
    worldState.updater().reset();

    assertThat(rootWith(Map.of(CONTRACT, Map.of(S1, UInt256.valueOf(10))), 1)).isEqualTo(expected);
  }

  @Test
  void aPredictedSlotTheBlockNeverTouchedFallsBackToTheFullComputation() {
    final Hash expected = expectedRoot();
    worldState.updater().reset();
    final StorageSlotKey untouched = new StorageSlotKey(UInt256.valueOf(9));

    assertThat(
            rootWith(Map.of(CONTRACT, Map.of(S1, UInt256.valueOf(10), untouched, UInt256.ONE)), 1))
        .isEqualTo(expected);
  }

  @Test
  void anAccountWithoutPredictionsIsComputedAsBefore() {
    final Hash expected = expectedRoot();
    worldState.updater().reset();

    assertThat(rootWith(Map.of(), 0)).isEqualTo(expected);
  }
}
