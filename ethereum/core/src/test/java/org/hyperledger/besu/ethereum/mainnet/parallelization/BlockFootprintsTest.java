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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.Cell;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.Footprint;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.RawWrite;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.Read;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.Seeding;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockFootprints.Write;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

class BlockFootprintsTest {

  private static final Address CONTRACT =
      Address.fromHexString("0x00000000000000000000000000000000000c0de");
  private static final UInt256 KEY = UInt256.ONE;
  private static final Cell SLOT = new Cell(CONTRACT, new StorageSlotKey(KEY));
  private static final Cell OTHER_SLOT = new Cell(CONTRACT, new StorageSlotKey(UInt256.valueOf(2)));

  private static UInt256 v(final long value) {
    return UInt256.valueOf(value);
  }

  /**
   * A run that read the slot at {@code read} (parent {@code parent}) and left it at {@code write}.
   */
  private static Footprint readWrite(final long parent, final long read, final long write) {
    return footprint(
        new Read[] {new Read(SLOT, v(read), v(parent))},
        read == write ? new Write[0] : new Write[] {new Write(SLOT, v(write))},
        Set.of(),
        false);
  }

  private static Footprint footprint(
      final Read[] reads,
      final Write[] writes,
      final Set<Address> cleared,
      final boolean refreshed) {
    return new Footprint(null, reads, writes, cleared, refreshed, false, true);
  }

  @Test
  void aReaderStoredBeforeItsWriterIsInvalidatedByIt() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(1, readWrite(10, 10, 11), true);

    footprints.store(0, readWrite(10, 10, 11), true);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);
  }

  @Test
  void aReaderStoredAfterItsWriterInvalidatesItself() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);

    footprints.store(1, readWrite(10, 10, 11), true);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
  }

  @Test
  void aReaderOfTheValueItsWriterLeavesStaysValid() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);

    footprints.store(1, readWrite(10, 11, 12), true);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);
  }

  @Test
  void executedWritesReplaceWhatTheFootprintPredicted() {
    final BlockFootprints footprints = new BlockFootprints(3);
    // predicted to leave 11, which the reader read
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(1, readWrite(10, 11, 12), true);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);

    footprints.reportExecuted(0, List.of(new RawWrite(CONTRACT, KEY, v(50))), Set.of());
    footprints.applyReported();

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
  }

  @Test
  void aTakenFootprintKeepsItsPredictedWrites() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(1, readWrite(10, 11, 12), true);

    footprints.reportTaken(0);
    footprints.applyReported();

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);
    // a later reader still sees what the taken transaction wrote
    footprints.store(2, readWrite(10, 10, 11), true);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(2);
  }

  @Test
  void anExecutedPositionTakesNoFootprintAndIsNotInvalidated() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.reportExecuted(1, List.of(), Set.of());
    footprints.applyReported();

    assertThat(footprints.store(1, readWrite(10, 10, 11), false)).isFalse();
    footprints.store(0, readWrite(10, 10, 11), true);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);
  }

  @Test
  void onlyIfEmptyKeepsTheFootprintStoredFirst() {
    final BlockFootprints footprints = new BlockFootprints(2);
    final Footprint refreshed =
        footprint(new Read[] {new Read(SLOT, v(11), v(10))}, new Write[0], Set.of(), true);
    footprints.store(1, refreshed, false);

    assertThat(footprints.store(1, readWrite(10, 10, 11), true)).isFalse();
    assertThat(footprints.latest(1)).isSameAs(refreshed);
  }

  @Test
  void seedsAreTheValuesTheWritesBeforeLeave() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);
    final Footprint reader = readWrite(10, 10, 11);
    footprints.store(1, reader, true);

    final Seeding seeding = footprints.seedsFor(1, reader);

    assertThat(seeding).isNotNull();
    assertThat(seeding.seeds()).containsOnlyKeys(CONTRACT);
    assertThat(seeding.seeds().get(CONTRACT)).containsEntry(SLOT.slot(), v(11));
  }

  @Test
  void noSeedingWhenNothingMoved() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);
    final Footprint reader = readWrite(10, 11, 12);
    footprints.store(1, reader, true);

    assertThat(footprints.seedsFor(1, reader)).isNull();
  }

  @Test
  void aRefreshedFootprintReadingASeedWhoseWriterIsGoneMoves() {
    final BlockFootprints footprints = new BlockFootprints(3);
    // refreshed on 11, which no transaction before it writes any more: the slot is at the parent's
    final Footprint refreshed =
        footprint(new Read[] {new Read(SLOT, v(11), v(10))}, new Write[0], Set.of(), true);
    footprints.store(1, refreshed, false);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
    final Seeding seeding = footprints.seedsFor(1, refreshed);
    assertThat(seeding).isNotNull();
    assertThat(seeding.seeds()).isEmpty();
  }

  @Test
  void aClearedAccountIsNotTrackedAndItsReadersAreNotRefreshed() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, footprint(new Read[0], new Write[0], Set.of(CONTRACT), false), true);
    final Footprint reader = readWrite(10, 10, 11);
    footprints.store(1, reader, true);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);
    assertThat(footprints.seedsFor(1, reader)).isNull();
  }

  @Test
  void aSlotWrittenByTooManyTransactionsIsNoLongerTracked() {
    final int writers = BlockFootprints.MAX_INDEXED_PER_SLOT + 1;
    final BlockFootprints footprints = new BlockFootprints(writers + 1);
    for (int i = 0; i < writers; i++) {
      footprints.store(i, readWrite(10, 10 + i, 11 + i), true);
    }
    final Footprint reader = readWrite(10, 10, 11);
    footprints.store(writers, reader, true);

    assertThat(footprints.seedsFor(writers, reader)).isNull();
  }

  @Test
  void invalidatedPositionsAreTakenInBlockOrderAfterTheGivenOne() {
    final BlockFootprints footprints = new BlockFootprints(5);
    footprints.store(1, readWrite(10, 10, 11), true);
    footprints.store(2, readWrite(10, 10, 11), true);
    footprints.store(
        3,
        footprint(
            new Read[] {new Read(OTHER_SLOT, v(0), v(0)), new Read(SLOT, v(10), v(10))},
            new Write[0],
            Set.of(),
            false),
        true);
    footprints.store(0, readWrite(10, 10, 11), true);

    // block processing is at 1: the next one, 2, is left to it
    assertThat(footprints.takeInvalidated(2)).isEqualTo(3);
    assertThat(footprints.takeInvalidated(2)).isEqualTo(-1);
  }

  @Test
  void aStoredRefreshReplacesThePredictionItsReadersRelyOn() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(1, readWrite(10, 11, 12), true);
    footprints.store(2, readWrite(10, 12, 13), true);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);

    // position 1 refreshed onto another value: the reader after it relied on its old write
    footprints.store(
        1,
        footprint(
            new Read[] {new Read(SLOT, v(11), v(10))},
            new Write[] {new Write(SLOT, v(20))},
            Set.of(),
            true),
        false);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(2);
  }

  @Test
  void blockProcessingWaitsForARunningRefreshUntilItEnds() {
    final BlockFootprints footprints = new BlockFootprints(3);
    assertThat(footprints.awaitRefresh(1, 10_000)).isFalse();

    final CompletableFuture<Void> done = footprints.refreshStarts(1);
    assertThat(footprints.isRefreshing(1)).isTrue();
    CompletableFuture.runAsync(
        () -> footprints.refreshEnds(1, done),
        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));

    assertThat(footprints.awaitRefresh(1, 10_000)).isTrue();
    assertThat(done).isDone();
    assertThat(footprints.isRefreshing(1)).isFalse();
  }

  @Test
  void waitingForARefreshEndsAfterTheTimeout() {
    final BlockFootprints footprints = new BlockFootprints(3);
    final CompletableFuture<Void> done = footprints.refreshStarts(1);

    assertThat(footprints.awaitRefresh(1, 1)).isTrue();
    assertThat(done).isNotDone();
    assertThat(footprints.isRefreshing(1)).isTrue();
  }

  @Test
  void settledFirstSkipsAReaderWhoseWriterWaitsForARefreshOrRunsOne() {
    final BlockFootprints footprints = new BlockFootprints(4, true);
    footprints.store(0, readWrite(10, 10, 11), true);
    // read the parent's 10 where 0 leaves 11: invalidated, and writes 12
    footprints.store(1, readWrite(10, 10, 12), true);
    // read 11 where 1 leaves 12: invalidated, and its writer 1 is invalidated too
    footprints.store(2, readWrite(10, 11, 13), true);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
    final CompletableFuture<Void> done = footprints.refreshStarts(1);
    assertThat(footprints.takeInvalidated(-1)).as("1 is refreshed").isEqualTo(-1);

    footprints.refreshEnds(1, done);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(2);
  }

  @Test
  void withoutSettledFirstAReaderIsTakenWhileItsWriterIsRefreshed() {
    final BlockFootprints footprints = new BlockFootprints(4);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(1, readWrite(10, 10, 12), true);
    footprints.store(2, readWrite(10, 11, 13), true);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
    footprints.refreshStarts(1);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(2);
  }

  @Test
  void settledFirstIgnoresAWriterBlockProcessingReached() {
    final BlockFootprints footprints = new BlockFootprints(4, true);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(1, readWrite(10, 10, 12), true);
    footprints.store(2, readWrite(10, 11, 13), true);
    footprints.refreshStarts(1);

    footprints.mainThreadAt(1);
    assertThat(footprints.takeInvalidated(1)).isEqualTo(2);
  }

  @Test
  void settledFirstSkipsAChainMemberWhilePredecessorIsRefreshed() {
    final BlockFootprints footprints = new BlockFootprints(3, true);
    footprints.store(
        0,
        footprint(
            new Read[0],
            new Write[] {new Write(SLOT, v(11)), new Write(OTHER_SLOT, v(5))},
            Set.of(),
            false),
        true);
    // read the parent's 10 where 0 leaves 11
    footprints.store(1, readWrite(10, 10, 12), true);
    // read the parent's 0 where 0 leaves 5, and ran on top of 1
    footprints.store(
        2,
        footprint(new Read[] {new Read(OTHER_SLOT, v(0), v(0))}, new Write[0], Set.of(), false),
        true);
    footprints.chainedOn(2, 1);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
    final CompletableFuture<Void> done = footprints.refreshStarts(1);
    assertThat(footprints.takeInvalidated(-1)).as("its predecessor is refreshed").isEqualTo(-1);

    footprints.refreshEnds(1, done);
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(2);
  }

  @Test
  void aPositionRefreshedAsOftenAsAllowedIsLeftToBlockProcessing() {
    final BlockFootprints footprints = new BlockFootprints(3, false, 1);
    footprints.store(0, readWrite(10, 10, 11), true);
    final Footprint reader = readWrite(10, 10, 12);
    footprints.store(1, reader, true);

    assertThat(footprints.takeInvalidated(-1)).isEqualTo(1);
    assertThat(footprints.seedsFor(1, reader)).isNotNull();
    // refreshed on 11, then its writer moves to 20
    footprints.store(
        1,
        footprint(
            new Read[] {new Read(SLOT, v(11), v(10))},
            new Write[] {new Write(SLOT, v(12))},
            Set.of(),
            true),
        false);
    footprints.store(0, readWrite(10, 10, 20), false);

    assertThat(footprints.isInvalidated(1)).isTrue();
    assertThat(footprints.takeInvalidated(-1)).isEqualTo(-1);
    assertThat(footprints.refreshesCapped).isEqualTo(1);
  }

  @Test
  void aSlotIsPredictedAtTheValueTheWritersBeforeLeave() {
    final BlockFootprints footprints = new BlockFootprints(4);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(2, readWrite(10, 11, 13), true);

    assertThat(footprints.predicted(0, CONTRACT, SLOT.slot())).isNull();
    assertThat(footprints.predicted(1, CONTRACT, SLOT.slot())).isEqualTo(v(11));
    assertThat(footprints.predicted(3, CONTRACT, SLOT.slot())).isEqualTo(v(13));
    assertThat(footprints.predicted(3, CONTRACT, OTHER_SLOT.slot())).isNull();
  }

  @Test
  void aSlotOfAClearedAccountIsNotPredicted() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(1, footprint(new Read[0], new Write[0], Set.of(CONTRACT), false), true);

    assertThat(footprints.predicted(2, CONTRACT, SLOT.slot())).isNull();
  }

  @Test
  void theFinalWritesAreTheLastWriteOfEachSlot() {
    final BlockFootprints footprints = new BlockFootprints(4);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(2, readWrite(10, 11, 13), true);
    footprints.store(
        1,
        footprint(new Read[0], new Write[] {new Write(OTHER_SLOT, v(5))}, Set.of(), false),
        true);

    assertThat(footprints.predictedFinalWrites())
        .containsOnlyKeys(CONTRACT)
        .extractingByKey(CONTRACT)
        .isEqualTo(java.util.Map.of(SLOT.slot(), v(13), OTHER_SLOT.slot(), v(5)));
  }

  @Test
  void theFinalWritesLeaveOutAnAccountWithStorageAClearedOrUntrackedSlot() {
    final BlockFootprints footprints = new BlockFootprints(3);
    footprints.store(0, readWrite(10, 10, 11), true);
    footprints.store(1, footprint(new Read[0], new Write[0], Set.of(CONTRACT), false), true);

    assertThat(footprints.predictedFinalWrites()).isEmpty();
  }
}
