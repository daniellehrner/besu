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
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.task.EthTask;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.BytecodeRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.SnapDataRequest;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.AbstractSnapMessageData;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.services.tasks.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RequestDataStepBytecodeTest {

  private static final int CONFIGURED_COUNT = 84;
  private static final int RESPONSE_SIZE = AbstractSnapMessageData.SIZE_REQUEST.intValue();

  private final SnapSyncProcessState snapSyncState = mock(SnapSyncProcessState.class);
  private final SnapSyncConfiguration snapSyncConfiguration = mock(SnapSyncConfiguration.class);

  // the codes of the test by their hash, and how many of the requested codes the peer answers
  private final Map<Bytes32, Bytes> codes = new HashMap<>();
  private int answeredCodes = Integer.MAX_VALUE;

  private RequestDataStep requestDataStep;

  @BeforeEach
  public void setUp() {
    final BlockHeader pivot = new BlockHeaderTestFixture().buildHeader();
    when(snapSyncState.getPivotBlockHeader()).thenReturn(Optional.of(pivot));
    when(snapSyncConfiguration.getBytecodeCountPerRequest()).thenReturn(CONFIGURED_COUNT);

    requestDataStep =
        new RequestDataStep(
            mock(EthContext.class),
            new WorldStateStorageCoordinator(
                new BonsaiWorldStateKeyValueStorage(
                    new InMemoryKeyValueStorageProvider(),
                    new NoOpMetricsSystem(),
                    DataStorageConfiguration.DEFAULT_BONSAI_CONFIG)),
            snapSyncState,
            mock(SnapWorldDownloadState.class),
            snapSyncConfiguration,
            new NoOpMetricsSystem()) {
          @Override
          @SuppressWarnings("unchecked")
          EthTask<Map<Bytes32, Bytes>> createBytecodeTask(
              final List<Bytes32> codeHashes, final BlockHeader blockHeader) {
            final Map<Bytes32, Bytes> response = new HashMap<>();
            codeHashes.stream()
                .limit(answeredCodes)
                .forEach(codeHash -> response.put(codeHash, codes.get(codeHash)));
            final EthTask<Map<Bytes32, Bytes>> task = mock(EthTask.class);
            when(task.run()).thenReturn(CompletableFuture.completedFuture(response));
            return task;
          }
        };
  }

  @Test
  public void shouldAskForTheConfiguredCountBeforeAnyCodeArrived() {
    assertThat(requestDataStep.bytecodeCountPerRequest()).isEqualTo(CONFIGURED_COUNT);
  }

  @Test
  public void shouldAskForAsManyCodesAsFitAResponse() {
    receiveCodes(CONFIGURED_COUNT, 2048);

    assertThat(requestDataStep.bytecodeCountPerRequest()).isEqualTo(RESPONSE_SIZE / 2048);
  }

  @Test
  public void shouldGoByTheAverageSizeOfTheCodesReceived() {
    receiveCodes(10, 1024);
    receiveCodes(10, 3072);

    assertThat(requestDataStep.bytecodeCountPerRequest()).isEqualTo(RESPONSE_SIZE / 2048);
  }

  @Test
  public void shouldNotAskForLessThanTheConfiguredCount() {
    // the largest a code can be on mainnet, of which a response holds fewer than configured
    receiveCodes(20, 24_576);

    assertThat(requestDataStep.bytecodeCountPerRequest()).isEqualTo(CONFIGURED_COUNT);
  }

  @Test
  public void shouldNotAskForMoreThanAPeerLooksUp() {
    receiveCodes(CONFIGURED_COUNT, 45);

    assertThat(requestDataStep.bytecodeCountPerRequest())
        .isEqualTo(RequestDataStep.MAX_BYTECODE_COUNT_PER_REQUEST);
  }

  @Test
  public void shouldHandTheCodesOfAResponseToTheirRequests() {
    answeredCodes = 2;
    final List<Task<SnapDataRequest>> tasks = codeRequests(3, 100);

    final CompletableFuture<List<Task<SnapDataRequest>>> result =
        requestDataStep.requestCode(tasks);

    assertThat(result).isCompletedWithValue(tasks);
    assertThat(tasks.get(0).getData().isResponseReceived()).isTrue();
    assertThat(tasks.get(1).getData().isResponseReceived()).isTrue();
    // not answered, so it is requested again and does not count as received
    assertThat(tasks.get(2).getData().isResponseReceived()).isFalse();
  }

  private void receiveCodes(final int count, final int size) {
    requestDataStep.requestCode(codeRequests(count, size));
  }

  /** Requests for the given number of different codes of the given size. */
  private List<Task<SnapDataRequest>> codeRequests(final int count, final int size) {
    final List<Task<SnapDataRequest>> tasks = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final Bytes code =
          Bytes.concatenate(Bytes.ofUnsignedInt(codes.size()), Bytes.wrap(new byte[size - 4]));
      final Bytes32 codeHash = Bytes32.wrap(Hash.hash(code).getBytes());
      codes.put(codeHash, code);
      final BytecodeRequest request =
          SnapDataRequest.createBytecodeRequest(
              Bytes32.leftPad(Bytes.ofUnsignedInt(codes.size())), Hash.EMPTY_TRIE_HASH, codeHash);
      tasks.add(new StubTask(request));
    }
    return tasks;
  }
}
