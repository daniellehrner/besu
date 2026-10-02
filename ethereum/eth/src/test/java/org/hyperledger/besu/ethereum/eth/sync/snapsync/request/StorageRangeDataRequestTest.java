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
package org.hyperledger.besu.ethereum.eth.sync.snapsync.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapSyncMetricsManager;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapWorldDownloadState;
import org.hyperledger.besu.ethereum.proof.WorldStateProofProvider;
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

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

import kotlin.collections.ArrayDeque;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class StorageRangeDataRequestTest {

  private static final int SLOT_COUNT = 64_000;
  // a sixteenth of the storage, which holds about 4,000 of the slots
  private static final Bytes32 RANGE_START = Bytes32.rightPad(Bytes.of(0x10));
  private static final Bytes32 RANGE_END =
      Bytes32.wrap(Bytes.concatenate(Bytes.of(0x1f), Bytes.repeat((byte) 0xff, 31)));

  private final Hash accountHash = Hash.hash(Bytes.of(1));
  private final BonsaiWorldStateKeyValueStorage worldStateStorage =
      new BonsaiWorldStateKeyValueStorage(
          new InMemoryKeyValueStorageProvider(),
          new NoOpMetricsSystem(),
          DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);
  private final WorldStateStorageCoordinator worldStateStorageCoordinator =
      new WorldStateStorageCoordinator(worldStateStorage);
  private final WorldStateProofProvider worldStateProofProvider =
      new WorldStateProofProvider(worldStateStorageCoordinator);
  private final SnapWorldDownloadState downloadState = mock(SnapWorldDownloadState.class);

  private final TreeMap<Bytes32, Bytes> accountWithStorage = new TreeMap<>();

  private MerkleTrie<Bytes, Bytes> storageTrie;

  @BeforeEach
  public void setUp() {
    storageTrie =
        new StoredMerklePatriciaTrie<>(
            new StoredNodeFactory<>(
                (location, hash) ->
                    worldStateStorage.getAccountStorageTrieNode(accountHash, location, hash),
                Function.identity(),
                Function.identity()),
            MerkleTrie.EMPTY_TRIE_NODE_HASH);
    for (int i = 0; i < SLOT_COUNT; i++) {
      storageTrie.put(
          Hash.hash(UInt256.valueOf(i)).getBytes(),
          RLP.encode(out -> out.writeBytes(UInt256.ONE.toMinimalBytes())));
    }
    final BonsaiWorldStateKeyValueStorage.Updater updater = worldStateStorage.updater();
    storageTrie.commit(
        (location, hash, value) ->
            updater.putAccountStorageTrieNode(accountHash, location, hash, value));
    updater.commit();
  }

  @Test
  public void shouldSplitARangeAgainThatNeedsManyMoreResponses() {
    final StorageRangeDataRequest request = rangeRequest(1);
    respondWithSlots(request, 3);

    final List<StorageRangeDataRequest> children = childRequests(request);

    assertThat(children).hasSize(RangeManager.MAX_RANGE_COUNT);
    // together they cover the rest of the range
    assertThat(children.getFirst().getStartKeyHash())
        .isGreaterThan(request.getSlots().lastKey())
        .isLessThan(RANGE_END);
    assertThat(children.getLast().getEndKeyHash()).isEqualTo(RANGE_END);
  }

  @Test
  public void shouldNotSplitARangeAgainIntoRangesOfAFewResponses() {
    final StorageRangeDataRequest request = rangeRequest(1);
    // the rest takes about 30 more responses like this one, which sixteen ranges would share
    respondWithSlots(request, 130);

    final List<StorageRangeDataRequest> children = childRequests(request);

    assertThat(children).hasSize(1);
    assertThat(children.getFirst().getEndKeyHash()).isEqualTo(RANGE_END);
  }

  @Test
  public void shouldNotSplitARangeMoreThanTwice() {
    final StorageRangeDataRequest request = rangeRequest(2);
    respondWithSlots(request, 3);

    assertThat(childRequests(request)).hasSize(1);
  }

  @Test
  public void shouldLetTheRangesOfASplitBeSplitOnceMore() {
    final StorageRangeDataRequest wholeStorage =
        SnapDataRequest.createStorageRangeDataRequest(
            Hash.ZERO,
            Bytes32.wrap(accountHash.getBytes()),
            storageTrie.getRootHash(),
            RangeManager.MIN_RANGE,
            RangeManager.MAX_RANGE);
    respondWithSlots(wholeStorage, 3);

    final List<StorageRangeDataRequest> ranges = childRequests(wholeStorage);
    assertThat(ranges).hasSize(RangeManager.MAX_RANGE_COUNT);

    // each of them holds about 4,000 slots, of which a response with three covers very little
    final StorageRangeDataRequest range = ranges.get(1);
    respondWithSlots(range, 3);
    final List<StorageRangeDataRequest> rangesOfRange = childRequests(range);
    assertThat(rangesOfRange).hasSize(RangeManager.MAX_RANGE_COUNT);

    // and those are not split again, however little of them a response covers
    final StorageRangeDataRequest rangeOfRange = rangesOfRange.getFirst();
    respondWithSlots(rangeOfRange, 1);
    assertThat(childRequests(rangeOfRange)).hasSizeLessThanOrEqualTo(1);
  }

  @Test
  public void shouldKeepTheSplitDepthOfARangeThatIsRequestedAgain() {
    when(downloadState.getMetricsManager()).thenReturn(mock(SnapSyncMetricsManager.class));
    final Hash stateRoot = accountWithTheStorage();
    final StorageRangeDataRequest request =
        SnapDataRequest.createStorageRangeDataRequest(
            stateRoot,
            Bytes32.wrap(accountHash.getBytes()),
            storageTrie.getRootHash(),
            RANGE_START,
            RANGE_END,
            2);

    // the storage changed with a new pivot block: what the peer sends no longer fits the root
    respondWithSlotsOfAnotherStorage(request);

    // so the account is requested again, and with its answer the range
    final ArgumentCaptor<SnapDataRequest> requestedAgain =
        ArgumentCaptor.forClass(SnapDataRequest.class);
    verify(downloadState).enqueueRequest(requestedAgain.capture());
    final AccountRangeDataRequest accountRequest =
        (AccountRangeDataRequest) requestedAgain.getValue();
    accountRequest.addResponse(
        worldStateProofProvider,
        accountWithStorage,
        new ArrayDeque<>(
            worldStateProofProvider.getAccountProofRelatedNodes(
                stateRoot, Bytes32.wrap(accountHash.getBytes()))));
    final List<StorageRangeDataRequest> rangeAgain =
        accountRequest
            .getChildRequests(downloadState, worldStateStorageCoordinator, null)
            .map(StorageRangeDataRequest.class::cast)
            .toList();
    assertThat(rangeAgain).hasSize(1);
    assertThat(rangeAgain.getFirst().getStartKeyHash()).isEqualTo(RANGE_START);
    assertThat(rangeAgain.getFirst().getEndKeyHash()).isEqualTo(RANGE_END);

    // a range that was split twice is not split again, however little of it a response covers
    respondWithSlots(rangeAgain.getFirst(), 3);
    assertThat(childRequests(rangeAgain.getFirst())).hasSize(1);
  }

  private StorageRangeDataRequest rangeRequest(final int splitDepth) {
    return SnapDataRequest.createStorageRangeDataRequest(
        Hash.ZERO,
        Bytes32.wrap(accountHash.getBytes()),
        storageTrie.getRootHash(),
        RANGE_START,
        RANGE_END,
        splitDepth);
  }

  /** Answers the request the way a peer does that returns only the given number of slots. */
  private void respondWithSlots(final StorageRangeDataRequest request, final int slotCount) {
    final Bytes32 start = request.getStartKeyHash();
    final RangeStorageEntriesCollector collector =
        RangeStorageEntriesCollector.createCollector(
            start, request.getEndKeyHash(), slotCount, Integer.MAX_VALUE);
    final TrieIterator<Bytes> visitor = RangeStorageEntriesCollector.createVisitor(collector);
    final TreeMap<Bytes32, Bytes> slots =
        (TreeMap<Bytes32, Bytes>)
            storageTrie.entriesFrom(
                root ->
                    RangeStorageEntriesCollector.collectEntries(collector, visitor, root, start));
    final List<Bytes> proofs =
        new ArrayList<>(
            worldStateProofProvider.getStorageProofRelatedNodes(
                storageTrie.getRootHash(), Bytes32.wrap(accountHash.getBytes()), start));
    proofs.addAll(
        worldStateProofProvider.getStorageProofRelatedNodes(
            storageTrie.getRootHash(), Bytes32.wrap(accountHash.getBytes()), slots.lastKey()));
    request.addResponse(downloadState, worldStateProofProvider, slots, new ArrayDeque<>(proofs));
    assertThat(request.isResponseReceived()).isTrue();
  }

  /** Answers the request with slots that the storage root of the request does not cover. */
  private void respondWithSlotsOfAnotherStorage(final StorageRangeDataRequest request) {
    final TreeMap<Bytes32, Bytes> slots = new TreeMap<>();
    slots.put(RANGE_START, RLP.encode(out -> out.writeBytes(UInt256.valueOf(2).toMinimalBytes())));
    request.addResponse(downloadState, worldStateProofProvider, slots, new ArrayDeque<>());
    assertThat(request.isResponseReceived()).isTrue();
  }

  /**
   * Puts the account that holds the storage into a state trie of its own.
   *
   * @return the root of that state trie
   */
  private Hash accountWithTheStorage() {
    final MerkleTrie<Bytes, Bytes> accountTrie =
        new StoredMerklePatriciaTrie<>(
            new StoredNodeFactory<>(
                worldStateStorage::getAccountStateTrieNode,
                Function.identity(),
                Function.identity()),
            MerkleTrie.EMPTY_TRIE_NODE_HASH);
    final PmtStateTrieAccountValue account =
        new PmtStateTrieAccountValue(1, Wei.ONE, Hash.wrap(storageTrie.getRootHash()), Hash.EMPTY);
    final Bytes encodedAccount = RLP.encode(account::writeTo);
    accountTrie.put(accountHash.getBytes(), encodedAccount);
    final BonsaiWorldStateKeyValueStorage.Updater updater = worldStateStorage.updater();
    accountTrie.commit(updater::putAccountStateTrieNode);
    updater.commit();
    accountWithStorage.put(Bytes32.wrap(accountHash.getBytes()), encodedAccount);
    return Hash.wrap(accountTrie.getRootHash());
  }

  private List<StorageRangeDataRequest> childRequests(final StorageRangeDataRequest request) {
    return request
        .getChildRequests(downloadState, worldStateStorageCoordinator, null)
        .map(StorageRangeDataRequest.class::cast)
        .toList();
  }
}
