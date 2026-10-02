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

import org.hyperledger.besu.ethereum.eth.sync.snapsync.AccountRangeResumeTracker.Origin;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.AccountRangeResumeTracker.ResumeRange;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;

public class AccountRangeResumeTrackerTest {

  private static final Bytes32 PARTITION_END =
      Bytes32.fromHexString("0x0fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
  private static final Bytes32 OTHER_PARTITION_END =
      Bytes32.fromHexString("0x1fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
  private static final Bytes32 START_0 = key(0x00);
  private static final Bytes32 START_1 = key(0x01);
  private static final Bytes32 START_2 = key(0x02);
  private static final Bytes32 OTHER_START = key(0x10);

  private final AccountRangeResumeTracker tracker = new AccountRangeResumeTracker();

  @Test
  public void shouldResumeFromQueuedAccountRequest() {
    tracker.accountRequestEnqueued(START_0, PARTITION_END);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_0, PARTITION_END));
  }

  @Test
  public void shouldAdvanceToNextAccountRequestWhenResponseHasNoChildren() {
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.accountRequestEnqueued(START_1, PARTITION_END);
    tracker.accountRequestCompleted(START_0, PARTITION_END);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_1, PARTITION_END));
  }

  @Test
  public void shouldNotAdvancePastAccountRangeWithUnfinishedChildren() {
    final Origin origin = new Origin(PARTITION_END, START_0);
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.childEnqueued(origin);
    tracker.childEnqueued(origin);
    tracker.accountRequestEnqueued(START_1, PARTITION_END);
    tracker.accountRequestCompleted(START_0, PARTITION_END);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_0, PARTITION_END));

    tracker.childCompleted(origin);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_0, PARTITION_END));

    tracker.childCompleted(origin);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_1, PARTITION_END));
  }

  @Test
  public void shouldResumeFromOldestAccountRangeWithUnfinishedChildren() {
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.childEnqueued(new Origin(PARTITION_END, START_0));
    tracker.accountRequestEnqueued(START_1, PARTITION_END);
    tracker.accountRequestCompleted(START_0, PARTITION_END);
    tracker.childEnqueued(new Origin(PARTITION_END, START_1));
    tracker.accountRequestEnqueued(START_2, PARTITION_END);
    tracker.accountRequestCompleted(START_1, PARTITION_END);

    // the children of the second range finish first
    tracker.childCompleted(new Origin(PARTITION_END, START_1));

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_0, PARTITION_END));

    tracker.childCompleted(new Origin(PARTITION_END, START_0));

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_2, PARTITION_END));
  }

  @Test
  public void shouldKeepPartitionWhileChildrenOfItsLastAccountRangeAreUnfinished() {
    final Origin origin = new Origin(PARTITION_END, START_0);
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.childEnqueued(origin);
    // the last response of a partition has no follow-up account request
    tracker.accountRequestCompleted(START_0, PARTITION_END);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_0, PARTITION_END));

    tracker.childCompleted(origin);

    assertThat(tracker.resumeRanges()).isEmpty();
  }

  @Test
  public void shouldCountChildrenSpawnedByChildren() {
    final Origin origin = new Origin(PARTITION_END, START_0);
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.childEnqueued(origin);
    tracker.accountRequestCompleted(START_0, PARTITION_END);

    // a storage request that is continued by two further range requests
    tracker.childEnqueued(origin);
    tracker.childEnqueued(origin);
    tracker.childCompleted(origin);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_0, PARTITION_END));

    tracker.childCompleted(origin);
    tracker.childCompleted(origin);

    assertThat(tracker.resumeRanges()).isEmpty();
  }

  @Test
  public void shouldKeepAccountRequestThatWasQueuedTwiceUntilBothComplete() {
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.accountRequestCompleted(START_0, PARTITION_END);

    assertThat(tracker.resumeRanges()).containsExactly(new ResumeRange(START_0, PARTITION_END));

    tracker.accountRequestCompleted(START_0, PARTITION_END);

    assertThat(tracker.resumeRanges()).isEmpty();
  }

  @Test
  public void shouldTrackPartitionsIndependently() {
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.accountRequestEnqueued(OTHER_START, OTHER_PARTITION_END);
    tracker.accountRequestEnqueued(START_1, PARTITION_END);
    tracker.accountRequestCompleted(START_0, PARTITION_END);

    assertThat(tracker.resumeRanges())
        .containsExactlyInAnyOrder(
            new ResumeRange(START_1, PARTITION_END),
            new ResumeRange(OTHER_START, OTHER_PARTITION_END));
  }

  @Test
  public void shouldIgnoreCompletionOfUnknownRequests() {
    tracker.accountRequestCompleted(START_0, PARTITION_END);
    tracker.childCompleted(new Origin(PARTITION_END, START_0));

    assertThat(tracker.resumeRanges()).isEmpty();
  }

  @Test
  public void shouldForgetEverythingWhenCleared() {
    tracker.accountRequestEnqueued(START_0, PARTITION_END);
    tracker.childEnqueued(new Origin(PARTITION_END, START_0));

    tracker.clear();

    assertThat(tracker.resumeRanges()).isEmpty();
  }

  private static Bytes32 key(final int firstByte) {
    final byte[] bytes = new byte[32];
    bytes[0] = (byte) firstByte;
    return Bytes32.wrap(bytes);
  }
}
