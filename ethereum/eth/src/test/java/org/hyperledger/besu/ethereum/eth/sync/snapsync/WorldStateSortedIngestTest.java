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
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE;

import org.hyperledger.besu.ethereum.eth.sync.snapsync.WorldStateSortedIngest.Batch;
import org.hyperledger.besu.plugin.services.storage.SegmentBulkLoad;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.SortedSegmentWriter;
import org.hyperledger.besu.services.kvstore.SegmentedInMemoryKeyValueStorage;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

public class WorldStateSortedIngestTest {

  private static final Bytes32 PARTITION_END =
      Bytes32.fromHexString("0x0fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
  private static final Bytes32 RANGE_END =
      Bytes32.fromHexString("0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
  private static final Bytes32 OTHER_RANGE_END = Bytes32.repeat((byte) 0xff);
  private static final Bytes32 WHOLE_STORAGE = Bytes32.repeat((byte) 0xff);
  // only matters for a range that is split
  private static final Bytes32 ANY_START = Bytes32.ZERO;

  // all in the storage partition of the accounts whose hash starts with the nibble 1
  private static final Bytes32 ACCOUNT_1 = account(0x11);
  private static final Bytes32 ACCOUNT_2 = account(0x12);
  private static final Bytes32 ACCOUNT_3 = account(0x13);
  private static final Bytes32 ACCOUNT_4 = account(0x14);

  // small enough for a test to cross with a single entry
  private static final WorldStateSortedIngest.Limits LIMITS =
      new WorldStateSortedIngest.Limits(4096, 512, 3072, 16_384);

  private final RecordingStorage storage = new RecordingStorage();
  private final WorldStateSortedIngest ingest =
      new WorldStateSortedIngest(storage, Runnable::run, LIMITS);
  private final List<String> stored = new ArrayList<>();

  @Test
  public void shouldKeepAccountRangesOfAPartitionInOneFileUntilThePartitionIsComplete() {
    ingest.writeAccountRange(PARTITION_END, accounts("first", 0x01, 0x02), false);
    ingest.writeAccountRange(PARTITION_END, accounts("second", 0x03), false);

    assertThat(storage.events).isEmpty();
    assertThat(stored).isEmpty();
    assertThat(storage.get(ACCOUNT_INFO_STATE, key(0x01))).isEmpty();

    ingest.writeAccountRange(PARTITION_END, accounts("third", 0x04), true);

    assertThat(storage.events).containsExactly("file ACCOUNT_INFO_STATE [01, 02, 03, 04]");
    assertThat(stored).containsExactly("first", "second", "third");
    assertThat(storage.get(ACCOUNT_INFO_STATE, key(0x03))).isPresent();
  }

  @Test
  public void shouldWriteEverySegmentOfABatchToAFileOfItsOwn() {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    collected.put(ACCOUNT_INFO_STATE, key(0x01), value());
    collected.put(TRIE_BRANCH_STORAGE, path(0x00, 0x09), value());
    collected.put(TRIE_BRANCH_STORAGE, path(0x00, 0x08), value());

    ingest.writeAccountRange(PARTITION_END, collected.toBatch(() -> stored.add("only")), true);

    assertThat(storage.events)
        .containsExactly("file ACCOUNT_INFO_STATE [01]", "file TRIE_BRANCH_STORAGE [0008, 0009]");
    assertThat(stored).containsExactly("only");
  }

  @Test
  public void shouldKeepTheAccountAfterThePartitionEndOutOfTheFile() {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    collected.put(ACCOUNT_INFO_STATE, key(0x0e), value());
    // the first account of the next partition, which a peer adds to prove the end of this one
    collected.put(ACCOUNT_INFO_STATE, key(0x10), value());
    collected.put(TRIE_BRANCH_STORAGE, path(0x00, 0x0e), value());
    collected.put(TRIE_BRANCH_STORAGE, path(0x01, 0x00), value());

    ingest.writeAccountRange(PARTITION_END, collected.toBatch(() -> stored.add("last")), true);

    assertThat(storage.events)
        .containsExactly("file ACCOUNT_INFO_STATE [0e]", "file TRIE_BRANCH_STORAGE [000e]");
    // the response is not stored before all of it is
    assertThat(stored).isEmpty();

    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_INFO_STATE [0e]",
            "file TRIE_BRANCH_STORAGE [000e]",
            "direct ACCOUNT_INFO_STATE [10]",
            "direct TRIE_BRANCH_STORAGE [0100]");
    assertThat(stored).containsExactly("last");
  }

  @Test
  public void shouldKeepATrieNodeOnThePathToThePartitionEndInTheFile() {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    // a prefix of the path of the partition end, and so before it
    collected.put(TRIE_BRANCH_STORAGE, path(0x00, 0x0f), value());

    ingest.writeAccountRange(PARTITION_END, collected.toBatch(() -> stored.add("only")), true);

    assertThat(storage.events).containsExactly("file TRIE_BRANCH_STORAGE [000f]");
    assertThat(stored).containsExactly("only");
  }

  @Test
  public void shouldDropTheSlotAfterTheEndOfARange() {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    collected.put(ACCOUNT_STORAGE_STORAGE, slotKey(ACCOUNT_1, 0x7f).toArrayUnsafe(), value());
    // the first slot of the next range, which a peer adds to prove the end of this one
    collected.put(ACCOUNT_STORAGE_STORAGE, slotKey(ACCOUNT_1, 0x80).toArrayUnsafe(), value());
    collected.put(
        TRIE_BRANCH_STORAGE,
        Bytes.concatenate(ACCOUNT_1, Bytes.of(0x07, 0x0f)).toArrayUnsafe(),
        value());
    collected.put(
        TRIE_BRANCH_STORAGE,
        Bytes.concatenate(ACCOUNT_1, Bytes.of(0x08, 0x00)).toArrayUnsafe(),
        value());

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, collected.toBatch(() -> stored.add("range")), List.of());

    assertThat(storage.events)
        .containsExactly(
            "direct ACCOUNT_STORAGE_STORAGE [11:7f]", "direct TRIE_BRANCH_STORAGE [11:07]");
    assertThat(stored).containsExactly("range");
  }

  @Test
  public void shouldStoreTheOpenFileBeforeAccountsThatDoNotFollowIt() {
    ingest.writeAccountRange(PARTITION_END, accounts("first", 0x01, 0x02), false);
    ingest.writeAccountRange(PARTITION_END, accounts("again", 0x02), false);

    assertThat(storage.events)
        .containsExactly("file ACCOUNT_INFO_STATE [01, 02]", "direct ACCOUNT_INFO_STATE [02]");
    assertThat(stored).containsExactly("first", "again");
  }

  @Test
  public void shouldStoreTheFileOfAPartitionWhenItReachesTheTargetSize() {
    ingest.writeAccountRange(
        PARTITION_END, batch("big", ACCOUNT_INFO_STATE, LIMITS.targetFileSize(), key(0x01)), false);

    assertThat(storage.events).containsExactly("file ACCOUNT_INFO_STATE [01]");
    assertThat(stored).containsExactly("big");
  }

  @Test
  public void shouldDropTheLeafOfTheSlotAfterTheRangeWhenItIsOnThePathToTheEndOfTheRange() {
    final Bytes32 rangeEnd =
        Bytes32.fromHexString("0x556394ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
    final Bytes32 lastSlot = Bytes32.rightPad(Bytes.fromHexString("0x556380"));
    final Bytes32 slotAfterTheRange = Bytes32.rightPad(Bytes.fromHexString("0x55639f"));
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    collected.put(ACCOUNT_STORAGE_STORAGE, storageKey(ACCOUNT_1, lastSlot), value());
    collected.put(ACCOUNT_STORAGE_STORAGE, storageKey(ACCOUNT_1, slotAfterTheRange), value());
    collected.put(TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 8)), value());
    // nothing else is below 55639, so the leaf of the slot after the range sits right there,
    // before the path of the end of the range
    collected.put(TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9)), value());

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, rangeEnd, collected.toBatch(() -> stored.add("range")), List.of());

    assertThat(storage.get(ACCOUNT_STORAGE_STORAGE, storageKey(ACCOUNT_1, lastSlot))).isPresent();
    assertThat(storage.get(ACCOUNT_STORAGE_STORAGE, storageKey(ACCOUNT_1, slotAfterTheRange)))
        .isEmpty();
    assertThat(storage.get(TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 8))))
        .isPresent();
    assertThat(storage.get(TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9))))
        .isEmpty();
    assertThat(stored).containsExactly("range");
  }

  @Test
  public void shouldKeepATrieNodeAboveTheLeafOfTheSlotAfterTheRange() {
    final Bytes32 rangeEnd =
        Bytes32.fromHexString("0x556394ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
    final Bytes32 slotAfterTheRange = Bytes32.rightPad(Bytes.fromHexString("0x55639f"));
    final Bytes32 anotherSlotAfterTheRange = Bytes32.rightPad(Bytes.fromHexString("0x55639fa0"));
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    collected.put(ACCOUNT_STORAGE_STORAGE, storageKey(ACCOUNT_1, slotAfterTheRange), value());
    collected.put(
        ACCOUNT_STORAGE_STORAGE, storageKey(ACCOUNT_1, anotherSlotAfterTheRange), value());
    // spans the end of the range: on the path to it, and above the leaves of both slots
    collected.put(TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9)), value());
    collected.put(
        TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9, 0xf, 0)), value());
    collected.put(
        TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9, 0xf, 0xa)), value());

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, rangeEnd, collected.toBatch(() -> stored.add("range")), List.of());

    assertThat(storage.get(TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9))))
        .isPresent();
    assertThat(
            storage.get(
                TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9, 0xf, 0))))
        .isEmpty();
    assertThat(
            storage.get(
                TRIE_BRANCH_STORAGE, storageKey(ACCOUNT_1, Bytes.of(5, 5, 6, 3, 9, 0xf, 0xa))))
        .isEmpty();
  }

  @Test
  public void shouldKeepStorageOfAscendingAccountsInOneFile() {
    ingest.writeStorageStart(
        ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01, 0x02), List.of());
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());

    assertThat(storage.events).isEmpty();

    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly("file ACCOUNT_STORAGE_STORAGE [11:01, 11:02, 12:01]");
    assertThat(stored).containsExactly("one", "two");
    assertThat(ingest.isActive()).isFalse();
  }

  @Test
  public void shouldHoldBackStorageOfAnAccountInsideTheOpenFileUntilTheFileIsStored() {
    ingest.writeStorageStart(ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01), List.of());
    ingest.writeStorageStart(ACCOUNT_3, WHOLE_STORAGE, slots("three", ACCOUNT_3, 0x01), List.of());
    // requested again after the storage of a later account went into the file
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());

    assertThat(storage.events).isEmpty();
    assertThat(stored).isEmpty();

    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:01, 13:01]",
            "direct ACCOUNT_STORAGE_STORAGE [12:01]");
    assertThat(stored).containsExactly("one", "three", "two");
  }

  @Test
  public void shouldWriteStorageOfAnAccountBeforeTheOpenFileAtOnce() {
    ingest.writeStorageStart(
        ACCOUNT_2,
        WHOLE_STORAGE,
        batch("large", ACCOUNT_STORAGE_STORAGE, LIMITS.targetFileSize(), ACCOUNT_2),
        List.of());
    ingest.writeStorageStart(ACCOUNT_3, WHOLE_STORAGE, slots("three", ACCOUNT_3, 0x01), List.of());
    storage.events.clear();

    ingest.writeStorageStart(ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01), List.of());

    assertThat(storage.events).containsExactly("direct ACCOUNT_STORAGE_STORAGE [11:01]");
    assertThat(stored).containsExactly("large", "one");
  }

  @Test
  public void shouldPutTheRangesThatContinueAStorageIntoTheFileOfThePartition() {
    ingest.writeStorageStart(
        ACCOUNT_1,
        WHOLE_STORAGE,
        slots("start", ACCOUNT_1, 0x01),
        List.of(RANGE_END, OTHER_RANGE_END));
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());
    // the ranges are downloaded side by side and complete in any order
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, OTHER_RANGE_END, slots("upper", ACCOUNT_1, 0x90), List.of());
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("lower", ACCOUNT_1, 0x05), List.of(RANGE_END));

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("lower end", ACCOUNT_1, 0x06), List.of());
    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly("file ACCOUNT_STORAGE_STORAGE [11:01, 11:05, 11:06, 11:90, 12:01]");
    assertThat(stored).containsExactly("start", "lower", "lower end", "upper", "two");
    assertThat(ingest.directBatches()).isZero();
  }

  @Test
  public void shouldNotWaitForAnAccountWhoseRangesHoldMoreThanTheLimit() {
    ingest.writeStorageStart(
        ACCOUNT_1, WHOLE_STORAGE, slots("start", ACCOUNT_1, 0x01), List.of(RANGE_END));
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());

    assertThat(storage.events).isEmpty();

    ingest.writeStorageContinuation(
        ACCOUNT_1,
        ANY_START,
        RANGE_END,
        batch(
            "range",
            ACCOUNT_STORAGE_STORAGE,
            LIMITS.queuedAccountLimit(),
            slotKey(ACCOUNT_1, 0x05)),
        List.of(RANGE_END));

    // the file of the partition ends with the start of the account, so that nothing spans its
    // ranges, which get files of their own
    assertThat(storage.events).containsExactly("file ACCOUNT_STORAGE_STORAGE [11:01]");
    assertThat(stored).containsExactly("start");

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("range end", ACCOUNT_1, 0x06), List.of());

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:01]", "file ACCOUNT_STORAGE_STORAGE [11:05, 11:06]");

    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:01]",
            "file ACCOUNT_STORAGE_STORAGE [11:05, 11:06]",
            "file ACCOUNT_STORAGE_STORAGE [12:01]");
    assertThat(stored).containsExactly("start", "range", "range end", "two");
  }

  @Test
  public void shouldStopWaitingWhenTooMuchIsHeldInMemory() {
    ingest.writeStorageStart(
        ACCOUNT_1, WHOLE_STORAGE, slots("start", ACCOUNT_1, 0x01), List.of(RANGE_END));
    // just below the size at which the file of the partition is stored
    final long valueSize = LIMITS.targetFileSize() - Bytes32.SIZE - 1;
    int account = 0x12;
    for (long held = 0; held <= LIMITS.bufferLimit(); held += valueSize + Bytes32.SIZE) {
      assertThat(storage.events).isEmpty();
      ingest.writeStorageStart(
          account(account),
          WHOLE_STORAGE,
          batch("held " + account, ACCOUNT_STORAGE_STORAGE, valueSize, account(account)),
          List.of());
      account++;
    }

    // the accounts behind the one that was waited for went into the file of the partition
    assertThat(storage.events).first().isEqualTo("file ACCOUNT_STORAGE_STORAGE [11:01]");
    assertThat(storage.events).hasSizeGreaterThan(1);
    assertThat(stored).startsWith("start", "held 18");

    // the range of the account is not waited for any more
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("range", ACCOUNT_1, 0x05), List.of());

    assertThat(storage.events).last().isEqualTo("direct ACCOUNT_STORAGE_STORAGE [11:05]");
  }

  @Test
  public void shouldStoreWhatAnAccountHasWhenItsRangesNeverComplete() {
    ingest.writeStorageStart(
        ACCOUNT_1, WHOLE_STORAGE, slots("start", ACCOUNT_1, 0x01), List.of(RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("range", ACCOUNT_1, 0x05), List.of(RANGE_END));
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());

    ingest.finishAll();

    assertThat(storage.get(ACCOUNT_STORAGE_STORAGE, slotKey(ACCOUNT_1, 0x01).toArrayUnsafe()))
        .isPresent();
    assertThat(storage.get(ACCOUNT_STORAGE_STORAGE, slotKey(ACCOUNT_1, 0x05).toArrayUnsafe()))
        .isPresent();
    assertThat(storage.get(ACCOUNT_STORAGE_STORAGE, slotKey(ACCOUNT_2, 0x01).toArrayUnsafe()))
        .isPresent();
    assertThat(stored).containsExactlyInAnyOrder("start", "range", "two");
  }

  @Test
  public void shouldHoldBackStorageOfAnAccountBehindTheOneThatIsWaitedFor() {
    // large enough to be stored at once, which leaves the partition without an open file
    ingest.writeStorageStart(
        ACCOUNT_1,
        WHOLE_STORAGE,
        batch("start", ACCOUNT_STORAGE_STORAGE, LIMITS.targetFileSize(), slotKey(ACCOUNT_1, 0x01)),
        List.of(RANGE_END));
    ingest.writeStorageStart(ACCOUNT_3, WHOLE_STORAGE, slots("three", ACCOUNT_3, 0x01), List.of());
    // requested again: the file that will hold the third account spans it
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());

    assertThat(storage.events).containsExactly("file ACCOUNT_STORAGE_STORAGE [11:01]");

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("range", ACCOUNT_1, 0x05), List.of());
    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:01]",
            "file ACCOUNT_STORAGE_STORAGE [11:05, 13:01]",
            "direct ACCOUNT_STORAGE_STORAGE [12:01]");
    assertThat(stored).containsExactly("start", "range", "three", "two");
  }

  @Test
  public void shouldKeepHoldingBackWhatIsBehindAnAccountWhoseRangesFillTheFile() {
    // the start and the first response of the range are each small enough to be waited for, and
    // together they are enough to have the file stored before the rest of the range is in it
    final long half = LIMITS.targetFileSize() / 2;
    assertThat(half).isLessThan(LIMITS.queuedAccountLimit());
    ingest.writeStorageStart(
        ACCOUNT_1,
        WHOLE_STORAGE,
        batch("start", ACCOUNT_STORAGE_STORAGE, half, slotKey(ACCOUNT_1, 0x01)),
        List.of(RANGE_END));
    ingest.writeStorageStart(ACCOUNT_3, WHOLE_STORAGE, slots("three", ACCOUNT_3, 0x01), List.of());
    // requested again: the file that will hold the third account spans it
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());
    ingest.writeStorageContinuation(
        ACCOUNT_1,
        ANY_START,
        RANGE_END,
        batch("range", ACCOUNT_STORAGE_STORAGE, half, slotKey(ACCOUNT_1, 0x05)),
        List.of(RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("range end", ACCOUNT_1, 0x06), List.of());

    assertThat(storage.events).containsExactly("file ACCOUNT_STORAGE_STORAGE [11:01, 11:05]");

    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:01, 11:05]",
            "file ACCOUNT_STORAGE_STORAGE [11:06, 13:01]",
            "direct ACCOUNT_STORAGE_STORAGE [12:01]");
  }

  @Test
  public void shouldKeepARangeThatIsSplitInItsPlaceInTheFileOfThePartition() {
    final Bytes32 firstHalfEnd = rangeEnd(0x3f);
    ingest.writeStorageStart(
        ACCOUNT_1,
        WHOLE_STORAGE,
        slots("start", ACCOUNT_1, 0x01),
        List.of(RANGE_END, OTHER_RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1, rangeStart(0x80), OTHER_RANGE_END, slots("upper", ACCOUNT_1, 0x90), List.of());
    // the lower range turns out to be large, and the rest of it is split in two
    ingest.writeStorageContinuation(
        ACCOUNT_1,
        rangeStart(0x02),
        RANGE_END,
        slots("lower", ACCOUNT_1, 0x05),
        List.of(firstHalfEnd, RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1, rangeStart(0x40), RANGE_END, slots("second half", ACCOUNT_1, 0x50), List.of());

    // the first half is still missing
    assertThat(stored).isEmpty();

    ingest.writeStorageContinuation(
        ACCOUNT_1, rangeStart(0x06), firstHalfEnd, slots("first half", ACCOUNT_1, 0x20), List.of());
    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly("file ACCOUNT_STORAGE_STORAGE [11:01, 11:05, 11:20, 11:50, 11:90]");
    assertThat(stored).containsExactly("start", "lower", "first half", "second half", "upper");
  }

  @Test
  public void shouldEndARangeWithFilesOfItsOwnWhenItIsSplit() {
    ingest.writeStorageContinuation(
        ACCOUNT_1,
        rangeStart(0x02),
        RANGE_END,
        batch(
            "range",
            ACCOUNT_STORAGE_STORAGE,
            LIMITS.rangeFileThreshold(),
            slotKey(ACCOUNT_1, 0x05)),
        List.of(rangeEnd(0x3f), RANGE_END));

    assertThat(storage.events).containsExactly("file ACCOUNT_STORAGE_STORAGE [11:05]");

    // the last of the ranges it was split into ends where it did, and is a stream of its own
    ingest.writeStorageContinuation(
        ACCOUNT_1, rangeStart(0x40), RANGE_END, slots("second half", ACCOUNT_1, 0x50), List.of());

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:05]", "direct ACCOUNT_STORAGE_STORAGE [11:50]");
    assertThat(stored).containsExactly("range", "second half");
  }

  @Test
  public void shouldWriteASmallRangeOfALargeContractThroughTheRegularWritePath() {
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("first", ACCOUNT_1, 0x05), List.of(RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("second", ACCOUNT_1, 0x06), List.of());

    assertThat(storage.events)
        .containsExactly(
            "direct ACCOUNT_STORAGE_STORAGE [11:05]", "direct ACCOUNT_STORAGE_STORAGE [11:06]");
    assertThat(stored).containsExactly("first", "second");
  }

  @Test
  public void shouldGiveARangeOfALargeContractFilesOfItsOwnOnceItIsLargeEnough() {
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("first", ACCOUNT_1, 0x05), List.of(RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1,
        ANY_START,
        RANGE_END,
        batch(
            "second",
            ACCOUNT_STORAGE_STORAGE,
            LIMITS.rangeFileThreshold(),
            slotKey(ACCOUNT_1, 0x06)),
        List.of(RANGE_END));

    assertThat(storage.events).isEmpty();

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("third", ACCOUNT_1, 0x07), List.of());

    assertThat(storage.events)
        .containsExactly("file ACCOUNT_STORAGE_STORAGE [11:05, 11:06, 11:07]");
    assertThat(stored).containsExactly("first", "second", "third");
  }

  @Test
  public void shouldKeepTheRangesOfALargeContractApart() {
    ingest.writeStorageContinuation(
        ACCOUNT_1,
        ANY_START,
        RANGE_END,
        batch(
            "lower",
            ACCOUNT_STORAGE_STORAGE,
            LIMITS.rangeFileThreshold(),
            slotKey(ACCOUNT_1, 0x05)),
        List.of(RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1,
        ANY_START,
        OTHER_RANGE_END,
        batch(
            "upper",
            ACCOUNT_STORAGE_STORAGE,
            LIMITS.rangeFileThreshold(),
            slotKey(ACCOUNT_1, 0x90)),
        List.of());

    assertThat(storage.events).containsExactly("file ACCOUNT_STORAGE_STORAGE [11:90]");

    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("lower end", ACCOUNT_1, 0x06), List.of());

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:90]", "file ACCOUNT_STORAGE_STORAGE [11:05, 11:06]");
  }

  @Test
  public void shouldHoldBackRangesOfALargeContractWhileTheOpenFileSpansIt() {
    ingest.writeStorageStart(ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01), List.of());
    ingest.writeStorageStart(ACCOUNT_3, WHOLE_STORAGE, slots("three", ACCOUNT_3, 0x01), List.of());
    // requested again after the storage of a later account went into the file
    ingest.writeStorageStart(
        ACCOUNT_2,
        WHOLE_STORAGE,
        slots("start", ACCOUNT_2, 0x01),
        List.of(RANGE_END, OTHER_RANGE_END));

    ingest.writeStorageContinuation(
        ACCOUNT_2,
        ANY_START,
        RANGE_END,
        batch(
            "range",
            ACCOUNT_STORAGE_STORAGE,
            LIMITS.rangeFileThreshold(),
            slotKey(ACCOUNT_2, 0x05)),
        List.of());
    ingest.writeStorageContinuation(
        ACCOUNT_2, ANY_START, OTHER_RANGE_END, slots("small range", ACCOUNT_2, 0x90), List.of());

    assertThat(storage.events).isEmpty();
    assertThat(stored).isEmpty();

    ingest.finishAll();

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:01, 13:01]",
            "direct ACCOUNT_STORAGE_STORAGE [12:01]",
            "file ACCOUNT_STORAGE_STORAGE [12:05]",
            "direct ACCOUNT_STORAGE_STORAGE [12:90]");
    assertThat(stored).containsExactly("one", "three", "start", "range", "small range");
  }

  @Test
  public void shouldStoreWhatARangeWroteBeforeABatchThatDoesNotFollowIt() {
    ingest.writeStorageContinuation(
        ACCOUNT_1,
        ANY_START,
        RANGE_END,
        batch(
            "first",
            ACCOUNT_STORAGE_STORAGE,
            LIMITS.rangeFileThreshold(),
            slotKey(ACCOUNT_1, 0x05)),
        List.of(RANGE_END));
    ingest.writeStorageContinuation(
        ACCOUNT_1, ANY_START, RANGE_END, slots("again", ACCOUNT_1, 0x05), List.of());

    assertThat(storage.events)
        .containsExactly(
            "file ACCOUNT_STORAGE_STORAGE [11:05]", "direct ACCOUNT_STORAGE_STORAGE [11:05]");
    assertThat(stored).containsExactly("first", "again");
  }

  @Test
  public void shouldKeepStoragePartitionsApart() {
    final Bytes32 otherPartitionAccount = account(0x21);
    ingest.writeStorageStart(ACCOUNT_4, WHOLE_STORAGE, slots("four", ACCOUNT_4, 0x01), List.of());
    ingest.writeStorageStart(
        otherPartitionAccount,
        WHOLE_STORAGE,
        slots("other", otherPartitionAccount, 0x01),
        List.of());
    // behind the account of the other partition, but not behind anything of its own partition
    ingest.writeStorageStart(
        account(0x15), WHOLE_STORAGE, slots("five", account(0x15), 0x01), List.of());

    ingest.finishAll();

    assertThat(storage.events)
        .containsExactlyInAnyOrder(
            "file ACCOUNT_STORAGE_STORAGE [14:01, 15:01]", "file ACCOUNT_STORAGE_STORAGE [21:01]");
    assertThat(ingest.directBatches()).isZero();
    assertThat(ingest.sortedFiles()).isEqualTo(2);
  }

  @Test
  public void shouldReportABatchWithoutEntriesAsStoredAtOnce() {
    ingest.writeStorageStart(ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01), List.of());
    ingest.writeStorageStart(
        ACCOUNT_2,
        WHOLE_STORAGE,
        new SortedIngestTransaction().toBatch(() -> stored.add("empty")),
        List.of());

    assertThat(stored).containsExactly("empty");
    assertThat(storage.events).isEmpty();
  }

  @Test
  public void shouldDropWhatIsNotStoredWhenClosed() {
    ingest.writeAccountRange(PARTITION_END, accounts("accounts", 0x01), false);
    ingest.writeStorageStart(ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01), List.of());
    ingest.writeStorageStart(ACCOUNT_3, WHOLE_STORAGE, slots("three", ACCOUNT_3, 0x01), List.of());
    ingest.writeStorageStart(ACCOUNT_2, WHOLE_STORAGE, slots("two", ACCOUNT_2, 0x01), List.of());

    ingest.close();
    ingest.finishAll();

    assertThat(storage.events).isEmpty();
    assertThat(stored).isEmpty();
    assertThat(storage.openWriters).isZero();
    assertThat(ingest.isActive()).isFalse();
  }

  @Test
  public void shouldKeepTheStorageInBulkLoadUntilEverythingIsStored() {
    assertThat(storage.bulkLoadEvents)
        .containsExactly(
            "started [ACCOUNT_INFO_STATE, ACCOUNT_STORAGE_STORAGE, TRIE_BRANCH_STORAGE]");

    ingest.writeStorageStart(ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01), List.of());
    ingest.finishAll();

    assertThat(storage.bulkLoadEvents).last().isEqualTo("closed after 1 files");
  }

  @Test
  public void shouldEndTheBulkLoadWhenClosed() {
    ingest.writeStorageStart(ACCOUNT_1, WHOLE_STORAGE, slots("one", ACCOUNT_1, 0x01), List.of());

    ingest.close();

    assertThat(storage.bulkLoadEvents).last().isEqualTo("closed after 0 files");
  }

  @Test
  public void shouldKeepTheLastOfSeveralEntriesWithTheSameKey() {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    collected.put(ACCOUNT_INFO_STATE, key(0x02), Bytes.of(1).toArrayUnsafe());
    collected.put(ACCOUNT_INFO_STATE, key(0x01), Bytes.of(2).toArrayUnsafe());
    collected.put(ACCOUNT_INFO_STATE, key(0x02), Bytes.of(3).toArrayUnsafe());

    ingest.writeAccountRange(PARTITION_END, collected.toBatch(() -> {}), true);

    assertThat(storage.events).containsExactly("file ACCOUNT_INFO_STATE [01, 02]");
    assertThat(storage.get(ACCOUNT_INFO_STATE, key(0x02))).contains(Bytes.of(3).toArrayUnsafe());
  }

  private Batch accounts(final String name, final int... firstBytes) {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    for (final int firstByte : firstBytes) {
      collected.put(ACCOUNT_INFO_STATE, key(firstByte), value());
    }
    return collected.toBatch(() -> stored.add(name));
  }

  private Batch slots(final String name, final Bytes32 account, final int... slots) {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    for (final int slot : slots) {
      collected.put(ACCOUNT_STORAGE_STORAGE, slotKey(account, slot).toArrayUnsafe(), value());
    }
    return collected.toBatch(() -> stored.add(name));
  }

  /** A batch with a single entry of the given size. */
  private Batch batch(
      final String name, final SegmentIdentifier segment, final long size, final Bytes key) {
    return batch(name, segment, size, key.toArrayUnsafe());
  }

  private Batch batch(
      final String name, final SegmentIdentifier segment, final long size, final byte[] key) {
    final SortedIngestTransaction collected = new SortedIngestTransaction();
    collected.put(segment, key, new byte[(int) size]);
    return collected.toBatch(() -> stored.add(name));
  }

  private static Bytes32 account(final int firstByte) {
    return Bytes32.rightPad(Bytes.of(firstByte));
  }

  private static Bytes32 rangeStart(final int firstByte) {
    return Bytes32.rightPad(Bytes.of(firstByte));
  }

  private static Bytes32 rangeEnd(final int firstByte) {
    return Bytes32.wrap(
        Bytes.concatenate(Bytes.of(firstByte), Bytes.repeat((byte) 0xff, Bytes32.SIZE - 1)));
  }

  private static Bytes slotKey(final Bytes32 account, final int slot) {
    return Bytes.concatenate(account, Bytes.of(slot));
  }

  private static byte[] storageKey(final Bytes32 account, final Bytes slotHashOrPath) {
    return Bytes.concatenate(account, slotHashOrPath).toArrayUnsafe();
  }

  private static byte[] key(final int firstByte) {
    return new byte[] {(byte) firstByte};
  }

  /** The key of a trie node of the account trie. */
  private static byte[] path(final int... nibbles) {
    return Bytes.of(nibbles).toArrayUnsafe();
  }

  private static byte[] value() {
    return new byte[] {1};
  }

  /**
   * Names a key of an account or of the account trie by its bytes, and a storage key by the first
   * byte of the account hash and the byte after the hash.
   */
  private static String name(final byte[] key) {
    return key.length > Bytes32.SIZE
        ? String.format("%02x:%02x", key[0], key[Bytes32.SIZE])
        : Bytes.wrap(key).toUnprefixedHexString();
  }

  /** Records what reaches the storage, in the order it does. */
  private static class RecordingStorage extends SegmentedInMemoryKeyValueStorage {
    private final List<String> events = new ArrayList<>();
    private final List<String> bulkLoadEvents = new ArrayList<>();
    private int openWriters;
    // the sorted writer of this storage is built on a transaction, which is not a direct write
    private boolean openingSortedWriter;

    @Override
    public SortedSegmentWriter sortedWriter(final SegmentIdentifier segment) {
      openingSortedWriter = true;
      final SortedSegmentWriter delegate;
      try {
        delegate = super.sortedWriter(segment);
      } finally {
        openingSortedWriter = false;
      }
      final List<String> keys = new ArrayList<>();
      openWriters++;
      return new SortedSegmentWriter() {
        @Override
        public void put(final byte[] key, final byte[] value) {
          keys.add(name(key));
          delegate.put(key, value);
        }

        @Override
        public long size() {
          return delegate.size();
        }

        @Override
        public void finish() {
          delegate.finish();
          events.add("file " + segment.getName() + " " + keys);
        }

        @Override
        public void close() {
          openWriters--;
          delegate.close();
        }
      };
    }

    @Override
    public SegmentBulkLoad startBulkLoad(final List<SegmentIdentifier> segments) {
      bulkLoadEvents.add("started " + segments.stream().map(SegmentIdentifier::getName).toList());
      return () -> bulkLoadEvents.add("closed after " + events.size() + " files");
    }

    @Override
    public SegmentedKeyValueStorageTransaction startTransaction() {
      final SegmentedKeyValueStorageTransaction delegate = super.startTransaction();
      if (openingSortedWriter) {
        return delegate;
      }
      final List<String> written = new ArrayList<>();
      return new SegmentedKeyValueStorageTransaction() {
        @Override
        public void put(final SegmentIdentifier segment, final byte[] key, final byte[] value) {
          written.add("direct " + segment.getName() + " [" + name(key) + "]");
          delegate.put(segment, key, value);
        }

        @Override
        public void remove(final SegmentIdentifier segment, final byte[] key) {
          delegate.remove(segment, key);
        }

        @Override
        public void commit() {
          delegate.commit();
          events.addAll(written);
        }

        @Override
        public void rollback() {
          delegate.rollback();
        }

        @Override
        public void close() {
          delegate.close();
        }
      };
    }
  }
}
