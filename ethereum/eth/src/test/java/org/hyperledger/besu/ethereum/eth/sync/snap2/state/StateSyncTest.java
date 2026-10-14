/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.eth.sync.snap2.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.eth.sync.snap2.FakeSnapNetwork;
import org.hyperledger.besu.ethereum.eth.sync.snap2.Snap2Configuration;
import org.hyperledger.besu.ethereum.eth.sync.snap2.TestWorld;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.SyncStorageFixture;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.SyncStorageFixture.Format;
import org.hyperledger.besu.ethereum.eth.sync.snap2.storage.SyncStorageFixture.Storages;
import org.hyperledger.besu.ethereum.eth.sync.snap2.trie.WorldStateTrieBuilder;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.trie.RangeManager;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(120)
class StateSyncTest {

  private final FakeSnapNetwork network = new FakeSnapNetwork();
  private StateSync stateSync;

  @AfterEach
  void tearDown() {
    if (stateSync != null) {
      stateSync.stop();
    }
    network.stop();
  }

  private static Snap2Configuration config() {
    return Snap2Configuration.getDefault().toBuilder()
        .accountChunks(16)
        .responseBytes(4096)
        .storageBatchSize(8)
        .maxStorageChunks(8)
        .codeBatchSize(4)
        .workerThreads(2)
        .trieBuildThreads(2)
        .build();
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void downloadsAStaticState(final Format format) throws Exception {
    final TestWorld world = TestWorld.random(new Random(3), 400, 3, 600);
    network.serve(world);
    final Storages storages = SyncStorageFixture.create(format);
    stateSync =
        new StateSync(
            storages.sync(),
            network,
            config().toBuilder().storageTriesWhileDownloading(true).build());

    stateSync.start(header(1, world.root())).get(60, TimeUnit.SECONDS);
    // every storage completed while downloading gets its trie from the written slots
    final int withStorage = world.storageLeaves().size();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> stateSync.storageTriesBuilt() == withStorage);

    final WorldStateTrieBuilder.Result built = build(storages);
    assertThat(built.root()).isEqualTo(world.root());
    assertThat(built.keptStorageTries()).isEqualTo(withStorage);
  }

  @Test
  void asksForAsManyCodesAsFitAResponse() throws Exception {
    final TestWorld world = TestWorld.random(new Random(3), 400, 3, 600);
    network.serve(world);
    stateSync = new StateSync(SyncStorageFixture.create(Format.BONSAI).sync(), network, config());

    stateSync.start(header(1, world.root())).get(60, TimeUnit.SECONDS);

    // codes of 1 to 300 bytes: a 4096 byte response holds far more than the 4 configured
    final double averageSize =
        world.codes().values().stream().mapToInt(Bytes::size).average().orElseThrow();
    assertThat(stateSync.codesPerRequest()).isCloseTo((int) (4096 / averageSize), within(2));
  }

  @Test
  void keepsTheConfiguredCodeCountWhenFewerFitAResponse() throws Exception {
    final TestWorld world = TestWorld.random(new Random(3), 400, 3, 600);
    network.serve(world);
    stateSync =
        new StateSync(
            SyncStorageFixture.create(Format.BONSAI).sync(),
            network,
            config().toBuilder().codeBatchSize(200).build());

    stateSync.start(header(1, world.root())).get(60, TimeUnit.SECONDS);

    assertThat(stateSync.codesPerRequest()).isEqualTo(200);
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void leavesTheStorageTriesToTheFinalBuildByDefault(final Format format) throws Exception {
    final TestWorld world = TestWorld.random(new Random(3), 400, 3, 600);
    network.serve(world);
    final Storages storages = SyncStorageFixture.create(format);
    stateSync = new StateSync(storages.sync(), network, config());

    stateSync.start(header(1, world.root())).get(60, TimeUnit.SECONDS);

    assertThat(stateSync.storageTriesBuilt()).isZero();
    final WorldStateTrieBuilder.Result built = build(storages);
    assertThat(built.root()).isEqualTo(world.root());
    assertThat(built.keptStorageTries()).isZero();
  }

  @Test
  void requestsTheAccountRangesLowestFirst() throws Exception {
    final TestWorld world = TestWorld.random(new Random(7), 2000, 0, 0);
    network.serve(world);
    network.holdResponses();
    final int ranges = 1024;
    stateSync =
        new StateSync(
            SyncStorageFixture.create(Format.BONSAI).sync(),
            network,
            config().toBuilder().accountChunks(ranges).build());
    final List<Bytes32> starts =
        List.copyOf(
            RangeManager.generateRanges(RangeManager.MIN_RANGE, RangeManager.MAX_RANGE, ranges)
                .keySet());

    final var completion = stateSync.start(header(1, world.root()));

    // with every answer held, the requests in flight are those of the lowest ranges
    await().pollDelay(Duration.ofMillis(200)).until(() -> !network.accountRangeOrigins().isEmpty());
    final List<Bytes32> inFlight = List.copyOf(network.accountRangeOrigins());
    assertThat(inFlight).hasSizeLessThan(ranges).isEqualTo(starts.subList(0, inFlight.size()));

    network.releaseResponses();
    completion.get(60, TimeUnit.SECONDS);
    // each range is started once, in key order
    assertThat(network.accountRangeOrigins().stream().filter(Set.copyOf(starts)::contains))
        .isSorted()
        .hasSize(ranges);
  }

  static Stream<Arguments> formatsAndSeeds() {
    return Stream.of(Format.values())
        .flatMap(
            format ->
                LongStream.of(11, 23, 37, 41, 59).mapToObj(seed -> Arguments.of(format, seed)));
  }

  @ParameterizedTest
  @MethodSource("formatsAndSeeds")
  void followsAMovingPivot(final Format format, final long seed) throws Exception {
    followMovingPivot(format, seed, config());
  }

  // the moves rewrite slots of storages whose trie is already built
  @ParameterizedTest
  @EnumSource(Format.class)
  void followsAMovingPivotWhileBuildingStorageTries(final Format format) throws Exception {
    followMovingPivot(format, 11, config().toBuilder().storageTriesWhileDownloading(true).build());
  }

  private void followMovingPivot(
      final Format format, final long seed, final Snap2Configuration config) throws Exception {
    final Random random = new Random(seed);
    TestWorld world = TestWorld.random(random, 1500, 6, 800);
    network.serve(world);
    network.latency(4);
    final Storages storages = SyncStorageFixture.create(format);
    stateSync = new StateSync(storages.sync(), network, config);
    final var completion = stateSync.start(header(100, world.root()));

    long block = 100;
    int moves = 0;
    for (int move = 0; move < 4 && !completion.isDone(); move++) {
      waitForProgress(progress() + 50);
      // no new request until the move is applied, but those in flight race with it
      stateSync.pause();
      final Bytes32 previousRoot = world.root();
      final TestWorld next = world.copy();
      final StateDiff diff = new StateDiff();
      for (int b = 0; b < 3; b++) {
        final BlockAccessList bal = next.mutate(random, 25);
        diff.add(bal);
        block++;
      }
      network.serve(next);
      stateSync.moveTo(header(block, next.root()), diff).get(30, TimeUnit.SECONDS);
      network.forget(previousRoot);
      stateSync.resume();
      world = next;
      moves++;
    }

    completion.get(60, TimeUnit.SECONDS);
    assertThat(moves).isGreaterThanOrEqualTo(3);
    assertThat(stateSync.pivot().header().getNumber()).isEqualTo(block);
    assertThat(buildRoot(storages)).isEqualTo(world.root());
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void leavesOutAPeerThatServesNoState(final Format format) throws Exception {
    final TestWorld world = TestWorld.random(new Random(13), 800, 3, 400);
    network.serve(world);
    network.serveNoState(1);
    // small responses make many requests, among which the few it got before being left out
    network.maxResponseBytes(1024);
    final Storages storages = SyncStorageFixture.create(format);
    stateSync = new StateSync(storages.sync(), network, config());

    stateSync.start(header(1, world.root())).get(60, TimeUnit.SECONDS);

    assertThat(buildRoot(storages)).isEqualTo(world.root());
    // its empty answers neither stalled the pivot nor kept it busy
    assertThat(stateSync.isPivotStale()).isFalse();
    assertThat(network.stateRequestsTo(network.peers().get(1)))
        .isLessThan(network.stateRequestCount() / 10);
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void detectsAStalePivot(final Format format) throws Exception {
    final Random random = new Random(5);
    final TestWorld world = TestWorld.random(random, 1500, 4, 800);
    network.serve(world);
    network.latency(4);
    final Storages storages = SyncStorageFixture.create(format);
    stateSync = new StateSync(storages.sync(), network, config());
    final var completion = stateSync.start(header(7, world.root()));
    waitForProgress(1);
    // the peers that served this root stop serving it, as when it gets too old
    network.forget(world.root());

    await().atMost(Duration.ofSeconds(20)).until(stateSync::isPivotStale);
    assertThat(completion).isNotDone();

    // a move to a served pivot resumes the download, which completes
    network.serve(world);
    stateSync.moveTo(header(8, world.root()), new StateDiff()).get(10, TimeUnit.SECONDS);
    completion.get(60, TimeUnit.SECONDS);
    assertThat(buildRoot(storages)).isEqualTo(world.root());
  }

  private long progress() {
    return stateSync.downloadedAccounts() + stateSync.downloadedSlots();
  }

  // polls often, so that the pivot moves before the download completes
  private void waitForProgress(final long target) {
    await()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(5))
        .atMost(Duration.ofSeconds(20))
        .until(() -> progress() >= target || stateSync.isComplete());
  }

  private Bytes32 buildRoot(final Storages storages) {
    return build(storages).root();
  }

  // the download stops first, as it does before the final build
  private WorldStateTrieBuilder.Result build(final Storages storages) {
    stateSync.stop();
    return new WorldStateTrieBuilder(storages.sync(), 2).build();
  }

  private static BlockHeader header(final long number, final Bytes32 root) {
    return new BlockHeaderTestFixture().number(number).stateRoot(Hash.wrap(root)).buildHeader();
  }
}
