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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.TrieGenerator;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.task.EthTask;
import org.hyperledger.besu.ethereum.eth.messages.snap.ImmutableSlotRangeData;
import org.hyperledger.besu.ethereum.eth.messages.snap.StorageRangeMessage;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.SnapDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.StorageRangeDataRequest;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.RangeManager;
import org.hyperledger.besu.ethereum.trie.RangeStorageEntriesCollector;
import org.hyperledger.besu.ethereum.trie.TrieIterator;
import org.hyperledger.besu.ethereum.trie.common.PmtStateTrieAccountValue;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.patricia.StoredMerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.patricia.StoredNodeFactory;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.services.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import kotlin.collections.ArrayDeque;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RequestDataStepStorageTest {

  private static final int ACCOUNT_COUNT = 4;

  private final BonsaiWorldStateKeyValueStorage worldStateStorage =
      new BonsaiWorldStateKeyValueStorage(
          new InMemoryKeyValueStorageProvider(),
          new NoOpMetricsSystem(),
          DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);
  private final WorldStateStorageCoordinator worldStateStorageCoordinator =
      new WorldStateStorageCoordinator(worldStateStorage);
  private final SnapSyncProcessState snapSyncState = mock(SnapSyncProcessState.class);
  private final SnapWorldDownloadState downloadState = mock(SnapWorldDownloadState.class);

  // the accounts each request was sent for, and what the peer answers to the requests in turn
  private final List<List<Bytes32>> requestedAccounts = new ArrayList<>();
  private final List<Integer> answeredAccountsPerRequest = new ArrayList<>();

  private MerkleTrie<Bytes, Bytes> accountTrie;
  private List<Task<SnapDataRequest>> tasks;
  private RequestDataStep requestDataStep;

  @BeforeEach
  public void setUp() {
    accountTrie = TrieGenerator.generateTrie(worldStateStorageCoordinator, ACCOUNT_COUNT);
    final Hash stateRoot = Hash.wrap(accountTrie.getRootHash());
    final BlockHeader pivot = new BlockHeaderTestFixture().stateRoot(stateRoot).buildHeader();
    when(snapSyncState.getPivotBlockHeader()).thenReturn(Optional.of(pivot));

    tasks = new ArrayList<>();
    for (int i = 1; i <= ACCOUNT_COUNT; i++) {
      final Bytes32 accountHash = accountHash(i);
      tasks.add(
          new StubTask(
              SnapDataRequest.createStorageRangeDataRequest(
                  stateRoot,
                  accountHash,
                  storageRoot(accountHash),
                  RangeManager.MIN_RANGE,
                  RangeManager.MAX_RANGE)));
    }

    requestDataStep =
        new RequestDataStep(
            mock(EthContext.class),
            worldStateStorageCoordinator,
            snapSyncState,
            downloadState,
            mock(SnapSyncConfiguration.class),
            new NoOpMetricsSystem()) {
          @Override
          EthTask<StorageRangeMessage.SlotRangeData> createStorageRangeTask(
              final List<Bytes32> accountHashes,
              final Bytes32 minRange,
              final Bytes32 maxRange,
              final BlockHeader blockHeader) {
            requestedAccounts.add(accountHashes);
            final int answered = answeredAccountsPerRequest.get(requestedAccounts.size() - 1);
            return answerWithStorageOf(accountHashes.subList(0, answered));
          }
        };
  }

  @Test
  public void shouldAskAgainForTheAccountsAPeerDidNotAnswer() {
    answeredAccountsPerRequest.addAll(List.of(2, 1, 1));

    final CompletableFuture<List<Task<SnapDataRequest>>> result =
        requestDataStep.requestStorage(tasks);

    assertThat(result).isCompletedWithValue(tasks);
    assertThat(requestedAccounts)
        .containsExactly(
            List.of(accountHash(1), accountHash(2), accountHash(3), accountHash(4)),
            List.of(accountHash(3), accountHash(4)),
            List.of(accountHash(4)));
    assertThat(tasks).allSatisfy(task -> assertThat(task.getData().isResponseReceived()).isTrue());
    assertThat(storageRequest(3).getSlots()).isEqualTo(slotsOf(accountHash(4)));
  }

  @Test
  public void shouldNotAskAgainWhenAPeerAnsweredNothing() {
    answeredAccountsPerRequest.add(0);

    final CompletableFuture<List<Task<SnapDataRequest>>> result =
        requestDataStep.requestStorage(tasks);

    assertThat(result).isCompletedWithValue(tasks);
    assertThat(requestedAccounts).hasSize(1);
    assertThat(tasks).noneSatisfy(task -> assertThat(task.getData().isResponseReceived()).isTrue());
  }

  @Test
  public void shouldAskOnlyOnceWhenAPeerAnsweredEveryAccount() {
    answeredAccountsPerRequest.add(ACCOUNT_COUNT);

    requestDataStep.requestStorage(tasks);

    assertThat(requestedAccounts).hasSize(1);
    assertThat(tasks).allSatisfy(task -> assertThat(task.getData().isResponseReceived()).isTrue());
  }

  @SuppressWarnings("unchecked")
  private EthTask<StorageRangeMessage.SlotRangeData> answerWithStorageOf(
      final List<Bytes32> accountHashes) {
    final ArrayDeque<NavigableMap<Bytes32, Bytes>> slots = new ArrayDeque<>();
    accountHashes.forEach(accountHash -> slots.add(slotsOf(accountHash)));
    final EthTask<StorageRangeMessage.SlotRangeData> task = mock(EthTask.class);
    // a peer that has nothing to say does not answer at all
    when(task.run())
        .thenReturn(
            accountHashes.isEmpty()
                ? CompletableFuture.failedFuture(new RuntimeException("no response"))
                : CompletableFuture.completedFuture(
                    ImmutableSlotRangeData.builder()
                        .slots(slots)
                        .proofs(new ArrayDeque<>())
                        .build()));
    return task;
  }

  private StorageRangeDataRequest storageRequest(final int taskIndex) {
    return (StorageRangeDataRequest) tasks.get(taskIndex).getData();
  }

  private static Bytes32 accountHash(final int account) {
    return Bytes32.leftPad(Bytes.of(account));
  }

  private Bytes32 storageRoot(final Bytes32 accountHash) {
    return Bytes32.wrap(
        PmtStateTrieAccountValue.readFrom(
                RLP.input(worldStateStorage.getAccount(Hash.wrap(accountHash)).orElseThrow()))
            .getStorageRoot()
            .getBytes());
  }

  /** The whole storage of an account, which a peer sends without a proof. */
  private NavigableMap<Bytes32, Bytes> slotsOf(final Bytes32 accountHash) {
    final StoredMerklePatriciaTrie<Bytes, Bytes> storageTrie =
        new StoredMerklePatriciaTrie<>(
            new StoredNodeFactory<>(
                (location, hash) ->
                    worldStateStorage.getAccountStorageTrieNode(
                        Hash.wrap(accountHash), location, hash),
                Function.identity(),
                Function.identity()),
            storageRoot(accountHash));
    final RangeStorageEntriesCollector collector =
        RangeStorageEntriesCollector.createCollector(
            RangeManager.MIN_RANGE, RangeManager.MAX_RANGE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    final TrieIterator<Bytes> visitor = RangeStorageEntriesCollector.createVisitor(collector);
    return new TreeMap<>(
        storageTrie.entriesFrom(
            root ->
                RangeStorageEntriesCollector.collectEntries(
                    collector, visitor, root, RangeManager.MIN_RANGE)));
  }
}
