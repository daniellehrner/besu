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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapSyncMetricsManager;
import org.hyperledger.besu.ethereum.proof.WorldStateProofProvider;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.RangeManager;
import org.hyperledger.besu.ethereum.trie.common.PmtStateTrieAccountValue;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.patricia.SimpleMerklePatriciaTrie;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.ExtraStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.ImmutableDataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.ImmutableExtraStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import kotlin.collections.ArrayDeque;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AccountRangeDataRequestTest {

  private static final Bytes SHARED_CODE = Bytes.of(1, 2, 3);
  private static final Bytes OTHER_CODE = Bytes.of(4, 5, 6);
  private static final Hash FIRST_ACCOUNT = Hash.wrap(Bytes32.leftPad(Bytes.of(1)));
  private static final Hash SECOND_ACCOUNT = Hash.wrap(Bytes32.leftPad(Bytes.of(2)));
  private static final Hash THIRD_ACCOUNT = Hash.wrap(Bytes32.leftPad(Bytes.of(3)));

  private final SnapRequestContext downloadState = mock(SnapRequestContext.class);
  private final Set<Bytes32> codeDownloadsUnderWay = new HashSet<>();

  @BeforeEach
  public void setUp() {
    when(downloadState.getMetricsManager()).thenReturn(mock(SnapSyncMetricsManager.class));
    when(downloadState.startCodeDownload(any()))
        .thenAnswer(invocation -> codeDownloadsUnderWay.add(invocation.getArgument(0)));
  }

  @Test
  public void shouldRequestACodeSharedByAccountsOnlyOnce() {
    final List<BytecodeRequest> codeRequests =
        codeRequests(storage(DataStorageConfiguration.DEFAULT_BONSAI_CONFIG));

    assertThat(codeRequests)
        .extracting(BytecodeRequest::getCodeHash)
        .containsExactlyInAnyOrder(hashOf(SHARED_CODE), hashOf(OTHER_CODE));
  }

  @Test
  public void shouldNotRequestACodeThatIsStored() {
    final BonsaiWorldStateKeyValueStorage storage =
        storage(DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);
    final BonsaiWorldStateKeyValueStorage.Updater updater = storage.updater();
    // stored for some account downloaded earlier
    updater.putCode(Hash.wrap(Bytes32.leftPad(Bytes.of(9))), Hash.hash(SHARED_CODE), SHARED_CODE);
    updater.commit();

    final List<BytecodeRequest> codeRequests = codeRequests(storage);

    assertThat(codeRequests)
        .extracting(BytecodeRequest::getCodeHash)
        .containsExactly(hashOf(OTHER_CODE));
    verify(downloadState, never()).startCodeDownload(hashOf(SHARED_CODE));
  }

  @Test
  public void shouldRequestTheCodeOfEveryAccountWhereItIsStoredByAccount() {
    final List<BytecodeRequest> codeRequests =
        codeRequests(
            storage(
                ImmutableDataStorageConfiguration.builder()
                    .dataStorageFormat(DataStorageFormat.BONSAI)
                    .extraStorageConfiguration(
                        ImmutableExtraStorageConfiguration.builder()
                            .unstable(
                                ImmutableExtraStorageConfiguration.Unstable.builder()
                                    .from(ExtraStorageConfiguration.Unstable.DEFAULT)
                                    .codeStoredByCodeHashEnabled(false)
                                    .build())
                            .build())
                    .build()));

    assertThat(codeRequests)
        .extracting(BytecodeRequest::getAccountHash)
        .containsExactlyInAnyOrder(
            Bytes32.wrap(FIRST_ACCOUNT.getBytes()),
            Bytes32.wrap(SECOND_ACCOUNT.getBytes()),
            Bytes32.wrap(THIRD_ACCOUNT.getBytes()));
  }

  /** The code requests of a response with two accounts that share their code and a third one. */
  private List<BytecodeRequest> codeRequests(final BonsaiWorldStateKeyValueStorage storage) {
    final WorldStateStorageCoordinator coordinator = new WorldStateStorageCoordinator(storage);
    final TreeMap<Bytes32, Bytes> accounts = new TreeMap<>();
    accounts.put(Bytes32.wrap(FIRST_ACCOUNT.getBytes()), accountWith(SHARED_CODE));
    accounts.put(Bytes32.wrap(SECOND_ACCOUNT.getBytes()), accountWith(SHARED_CODE));
    accounts.put(Bytes32.wrap(THIRD_ACCOUNT.getBytes()), accountWith(OTHER_CODE));
    final MerkleTrie<Bytes, Bytes> accountTrie =
        new SimpleMerklePatriciaTrie<>(Function.identity());
    accounts.forEach(accountTrie::put);

    final AccountRangeDataRequest request =
        SnapDataRequest.createAccountRangeDataRequest(
            Hash.wrap(accountTrie.getRootHash()), RangeManager.MIN_RANGE, RangeManager.MAX_RANGE);
    // the whole trie, which needs no proof
    request.addResponse(new WorldStateProofProvider(coordinator), accounts, new ArrayDeque<>());
    assertThat(request.isResponseReceived()).isTrue();

    return request
        .getChildRequests(downloadState, coordinator, null)
        .filter(BytecodeRequest.class::isInstance)
        .map(BytecodeRequest.class::cast)
        .toList();
  }

  private static BonsaiWorldStateKeyValueStorage storage(
      final DataStorageConfiguration configuration) {
    return new BonsaiWorldStateKeyValueStorage(
        new InMemoryKeyValueStorageProvider(), new NoOpMetricsSystem(), configuration);
  }

  private static Bytes accountWith(final Bytes code) {
    final PmtStateTrieAccountValue account =
        new PmtStateTrieAccountValue(1L, Wei.ONE, Hash.EMPTY_TRIE_HASH, Hash.hash(code));
    return RLP.encode(account::writeTo);
  }

  private static Bytes32 hashOf(final Bytes code) {
    return Bytes32.wrap(Hash.hash(code).getBytes());
  }
}
