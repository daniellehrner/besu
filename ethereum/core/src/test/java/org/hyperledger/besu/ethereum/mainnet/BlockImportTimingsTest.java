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
package org.hyperledger.besu.ethereum.mainnet;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.mainnet.BlockImportTimings.Phase;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Histogram;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BlockImportTimingsTest {

  @Test
  void phasesAccumulateAcrossRepeatedEntries() {
    final BlockImportTimings timings = BlockImportTimings.begin();
    try {
      assertThat(BlockImportTimings.time(Phase.TX_EXECUTE, () -> "a")).isEqualTo("a");
      BlockImportTimings.time(Phase.TX_EXECUTE, () -> {});
      final long start = System.nanoTime();
      BlockImportTimings.addSince(Phase.STATE_ROOT, start);
    } finally {
      timings.finish();
    }

    assertThat(timings.count(Phase.TX_EXECUTE)).isEqualTo(2);
    assertThat(timings.count(Phase.STATE_ROOT)).isEqualTo(1);
    assertThat(timings.count(Phase.TRIE_LOG)).isZero();
    assertThat(timings.nanos(Phase.TX_EXECUTE)).isGreaterThanOrEqualTo(0L);
    assertThat(timings.totalNanos())
        .isGreaterThanOrEqualTo(timings.nanos(Phase.TX_EXECUTE) + timings.nanos(Phase.STATE_ROOT));
    assertThat(timings.remainderNanos()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void hooksRunTheActionWhenNothingIsBeingTimed() {
    final boolean[] ran = {false};
    assertThat(BlockImportTimings.time(Phase.TX_REUSE, () -> 42)).isEqualTo(42);
    BlockImportTimings.time(Phase.TX_REUSE, () -> ran[0] = true);
    BlockImportTimings.addSince(Phase.TX_REUSE, System.nanoTime());
    assertThat(ran[0]).isTrue();
  }

  @Test
  void phaseTimedOnAnotherThreadIsNotAttributed() throws InterruptedException {
    final BlockImportTimings timings = BlockImportTimings.begin();
    try {
      final Thread other = new Thread(() -> BlockImportTimings.time(Phase.STATE_COMMIT, () -> {}));
      other.start();
      other.join();
    } finally {
      timings.finish();
    }
    assertThat(timings.count(Phase.STATE_COMMIT)).isZero();
  }

  @Test
  void finishDetachesFromTheThread() {
    final BlockImportTimings first = BlockImportTimings.begin();
    first.finish();
    BlockImportTimings.time(Phase.BODY_VALIDATION, () -> {});
    assertThat(first.count(Phase.BODY_VALIDATION)).isZero();
  }

  @Test
  void describeListsEnteredPhasesAndTotals() {
    final BlockImportTimings timings = BlockImportTimings.begin();
    try {
      BlockImportTimings.time(Phase.TX_REUSE, () -> {});
      BlockImportTimings.time(Phase.TX_REUSE, () -> {});
      BlockImportTimings.time(Phase.STATE_ROOT, () -> {});
    } finally {
      timings.finish();
    }

    final String description = timings.describe();
    assertThat(description).startsWith("reuse ").contains(" (2) | root ");
    assertThat(description).doesNotContain("trielog");
    assertThat(description).contains("| total ").contains("| cpu ").contains("| gc ");
    assertThat(description)
        .endsWith(String.format("| remainder %.1f", timings.remainderNanos() / 1e6));
  }

  @Test
  void recordsEnteredPhasesAndTotalsIntoTheHistogram() {
    final Map<String, Double> observed = new HashMap<>();
    final LabelledMetric<Histogram> histogram = labels -> amount -> observed.put(labels[0], amount);

    final BlockImportTimings timings = BlockImportTimings.begin();
    try {
      BlockImportTimings.time(Phase.TX_EXECUTE, () -> {});
    } finally {
      timings.finish();
    }
    timings.recordTo(histogram);

    assertThat(observed.keySet())
        .containsExactlyInAnyOrder("execute", "total", "cpu", "gc", "remainder");
    assertThat(observed.get("total")).isEqualTo(timings.totalNanos() / 1e9);
  }

  @Test
  void histogramIsCreatedOnTheBlockProcessingCategory() {
    assertThat(BlockImportTimings.createHistogram(new NoOpMetricsSystem())).isNotNull();
  }
}
