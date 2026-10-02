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
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_INFO_STATE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_STORAGE_STORAGE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.AccountRangeDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.BytecodeRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.SnapDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.StorageRangeDataRequest;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.patricia.StoredMerklePatriciaTrie;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.services.tasks.Task;

import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class PersistDataStepSortedIngestTest {

  private final BonsaiWorldStateKeyValueStorage worldStateStorage =
      new BonsaiWorldStateKeyValueStorage(
          new InMemoryKeyValueStorageProvider(),
          new NoOpMetricsSystem(),
          DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);
  private final WorldStateStorageCoordinator worldStateStorageCoordinator =
      new WorldStateStorageCoordinator(worldStateStorage);
  private final WorldStateSortedIngest sortedIngest =
      new WorldStateSortedIngest(worldStateStorage.getComposedWorldStateStorage(), Runnable::run);

  private final SnapSyncProcessState snapSyncState = mock(SnapSyncProcessState.class);
  private final SnapWorldDownloadState downloadState = mock(SnapWorldDownloadState.class);
  private final SnapSyncConfiguration snapSyncConfiguration = mock(SnapSyncConfiguration.class);

  private final PersistDataStep persistDataStep =
      new PersistDataStep(
          snapSyncState,
          worldStateStorageCoordinator,
          downloadState,
          snapSyncConfiguration,
          Optional.of(sortedIngest));

  private final List<Task<SnapDataRequest>> tasks = TaskGenerator.createAccountRequest(true, false);
  private final AccountRangeDataRequest accountRequest =
      (AccountRangeDataRequest) tasks.get(0).getData();
  private final StorageRangeDataRequest storageRequest =
      (StorageRangeDataRequest) tasks.get(1).getData();
  private final BytecodeRequest codeRequest = (BytecodeRequest) tasks.get(2).getData();
  // the requests let go of their responses once those are with the ingest
  private final NavigableMap<Bytes32, Bytes> accounts = accountRequest.getAccounts();
  private final NavigableMap<Bytes32, Bytes> slots = storageRequest.getSlots();

  @BeforeEach
  public void setUp() {
    when(downloadState.getMetricsManager()).thenReturn(mock(SnapSyncMetricsManager.class));
  }

  @Test
  public void shouldKeepStorageOutOfTheStorageUntilTheIngestStoresIt() {
    persistDataStep.persist(tasks);

    assertThat(worldStateStorage.getComposedWorldStateStorage().stream(ACCOUNT_STORAGE_STORAGE))
        .isEmpty();
    verify(downloadState, never()).onRequestStored(storageRequest);

    sortedIngest.finishAll();

    assertAccountsAndStoragePersisted();
    verify(downloadState).onRequestStored(storageRequest);
  }

  @Test
  public void shouldStoreTheAccountsOfAResponseThatCompletesItsPartitionAtOnce() {
    persistDataStep.persist(tasks);

    assertThat(worldStateStorage.getComposedWorldStateStorage().stream(ACCOUNT_INFO_STATE))
        .hasSameSizeAs(accounts.entrySet());
    verify(downloadState).onRequestStored(accountRequest);
  }

  @Test
  public void shouldLetGoOfTheResponsesThatWaitForTheirFile() {
    final AccountRangeDataRequest account = spy(accountRequest);
    final StorageRangeDataRequest storage = spy(storageRequest);

    persistDataStep.persist(List.of(new StubTask(account), new StubTask(storage)));

    // the storage is not stored yet, and its request is held until it is
    verify(downloadState, never()).onRequestStored(storage);
    final InOrder inOrder = inOrder(account, storage);
    inOrder.verify(account).persist(any(), any(), any(), any(), any());
    inOrder.verify(account).releaseResponse();
    inOrder.verify(storage).persist(any(), any(), any(), any(), any());
    inOrder.verify(storage).releaseResponse();
    // which leaves them complete for the step that follows
    assertThat(account.isResponseReceived()).isTrue();
    assertThat(storage.isResponseReceived()).isTrue();

    sortedIngest.finishAll();

    assertAccountsAndStoragePersisted();
    verify(downloadState).onRequestStored(storage);
  }

  @Test
  public void shouldAnnounceTheStorageOfAnAccountRangeBeforeItIsRequested() {
    final WorldStateSortedIngest ingest = spy(sortedIngest);
    final PersistDataStep persistStep =
        new PersistDataStep(
            snapSyncState,
            worldStateStorageCoordinator,
            downloadState,
            snapSyncConfiguration,
            Optional.of(ingest));

    persistStep.persist(List.of(tasks.get(0)));

    // once the request is queued its response can arrive, and has to find its place
    final InOrder inOrder = inOrder(ingest, downloadState);
    inOrder.verify(ingest).announceStorage(List.of(storageRequest.getAccountHash().getBytes()));
    inOrder.verify(downloadState).enqueueRequests(any());
  }

  @Test
  public void shouldStoreCodeThroughTheRegularWritePath() {
    persistDataStep.persist(tasks);

    assertThat(
            worldStateStorage.getCode(
                Hash.wrap(codeRequest.getCodeHash()), Hash.wrap(codeRequest.getAccountHash())))
        .isPresent();
    verify(downloadState).onRequestStored(codeRequest);
  }

  @Test
  public void shouldWriteRangeDataThroughTheRegularWritePathOnceTheIngestIsFinished() {
    sortedIngest.finishAll();

    persistDataStep.persist(tasks);

    assertAccountsAndStoragePersisted();
    verify(downloadState).onRequestStored(accountRequest);
    verify(downloadState).onRequestStored(storageRequest);
  }

  private void assertAccountsAndStoragePersisted() {
    final StoredMerklePatriciaTrie<Bytes, Bytes> accountTrie =
        new StoredMerklePatriciaTrie<>(
            worldStateStorageCoordinator::getAccountStateTrieNode,
            Bytes32.wrap(accountRequest.getRootHash().getBytes()),
            b -> b,
            b -> b);
    assertThat(accounts).isNotEmpty();
    accounts.forEach(
        (key, value) -> {
          assertThat(accountTrie.get(key)).isPresent();
          assertThat(worldStateStorage.getAccount(Hash.wrap(key))).isPresent();
        });

    final StoredMerklePatriciaTrie<Bytes, Bytes> storageTrie =
        new StoredMerklePatriciaTrie<>(
            (location, hash) ->
                worldStateStorageCoordinator.getAccountStorageTrieNode(
                    storageRequest.getAccountHash(), location, hash),
            storageRequest.getStorageRoot(),
            b -> b,
            b -> b);
    assertThat(slots).isNotEmpty();
    slots.forEach((key, value) -> assertThat(storageTrie.get(key)).isPresent());
    assertThat(worldStateStorage.getComposedWorldStateStorage().stream(ACCOUNT_STORAGE_STORAGE))
        .hasSameSizeAs(slots.entrySet());
  }
}
