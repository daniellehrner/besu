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

import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Histogram;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wall-clock breakdown of one block import on the thread that performs it.
 *
 * <p>The phases are the steps of the critical path from header validation to the persisted state,
 * with the transaction loop split into results reused from speculative execution and transactions
 * executed on this thread. Alongside the phases it records the importing thread's own CPU time and
 * the JVM garbage collection time that elapsed while it ran, so time the thread spent neither
 * computing nor collecting shows up as a separate remainder.
 *
 * <p>The instance lives in a thread-local for the duration of the import. The phases span
 * interfaces that have no room for an extra parameter, such as the world state persist, and the
 * import is single-threaded, so the thread-local avoids widening every signature on the way down.
 * When no import has been begun on the current thread the hooks run the timed code unchanged.
 */
public final class BlockImportTimings {

  /** A step of the block import or fork choice update. */
  public enum Phase {
    /** Header validation against the parent, before any state is touched. */
    HEADER_VALIDATION("header"),
    /** Resolving the parent world state to execute on. */
    WORLD_STATE_LOOKUP("worldstate"),
    /** System calls and other work before the first transaction. */
    PRE_EXECUTION("pre"),
    /** Handing the transactions to the speculative executor. */
    PARALLEL_DISPATCH("dispatch"),
    /** Taking over speculative results, including the conflict check and the state merge. */
    TX_REUSE("reuse"),
    /** Executing transactions on the importing thread. */
    TX_EXECUTE("execute"),
    /** Withdrawals, requests, block access list and coinbase work after the last transaction. */
    POST_EXECUTION("post"),
    /** Computing the state root. */
    STATE_ROOT("root"),
    /** Writing the trie log. */
    TRIE_LOG("trielog"),
    /** Committing the flat state and trie nodes to storage. */
    STATE_COMMIT("commit"),
    /** Receipts root, logs bloom and the other body checks. */
    BODY_VALIDATION("body"),
    /** Storing the block, its receipts and its access list. */
    STORE_BLOCK("store"),
    /** Moving the world state to the new head on a fork choice update. */
    FORK_CHOICE_WORLD_STATE("fcu_worldstate"),
    /** Forwarding or rewinding the chain head on a fork choice update. */
    FORK_CHOICE_CHAIN_HEAD("fcu_head"),
    /** Recording the finalized and safe blocks on a fork choice update. */
    FORK_CHOICE_FINALITY("fcu_finality"),
    /** Speculative results discarded because they conflicted with an earlier transaction. */
    TX_CONFLICT("conflict", true),
    /** Transactions re-executed because their speculative execution had not finished. */
    TX_UNFINISHED("unfinished", true);

    private final String label;
    private final boolean countOnly;

    Phase(final String label) {
      this(label, false);
    }

    Phase(final String label, final boolean countOnly) {
      this.label = label;
      this.countOnly = countOnly;
    }

    /**
     * Whether the phase only counts occurrences and carries no duration of its own.
     *
     * @return true for a count-only phase
     */
    public boolean isCountOnly() {
      return countOnly;
    }

    /**
     * The short name used in log lines and as the metric label.
     *
     * @return the label
     */
    public String label() {
      return label;
    }
  }

  private static final String TOTAL = "total";
  private static final String CPU = "cpu";
  private static final String GC = "gc";
  private static final String REMAINDER = "remainder";

  private static final Logger LOG = LoggerFactory.getLogger(BlockImportTimings.class);
  private static final ThreadLocal<BlockImportTimings> CURRENT = new ThreadLocal<>();
  private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
  private static final List<GarbageCollectorMXBean> COLLECTORS =
      ManagementFactory.getGarbageCollectorMXBeans();

  private final long[] phaseNanos = new long[Phase.values().length];
  private final int[] phaseCounts = new int[Phase.values().length];
  private final long startWallNanos;
  private final long startCpuNanos;
  private final long startGcMillis;
  private long totalNanos;
  private long cpuNanos;
  private long gcNanos;

  private BlockImportTimings() {
    startWallNanos = System.nanoTime();
    startCpuNanos = currentThreadCpuNanos();
    startGcMillis = collectionMillis();
  }

  /**
   * Starts timing an import on the current thread. Hooks reached from this thread record into the
   * returned instance until {@link #finish()} is called.
   *
   * @return the timings for this import
   */
  public static BlockImportTimings begin() {
    final BlockImportTimings timings = new BlockImportTimings();
    CURRENT.set(timings);
    return timings;
  }

  /** Stops timing, detaches from the thread and fixes the total, CPU and GC figures. */
  public void finish() {
    totalNanos = System.nanoTime() - startWallNanos;
    cpuNanos = currentThreadCpuNanos() - startCpuNanos;
    gcNanos = (collectionMillis() - startGcMillis) * 1_000_000L;
    CURRENT.remove();
  }

  /**
   * Runs the action and attributes its duration to the phase when an import is being timed on this
   * thread.
   *
   * @param phase the phase to attribute the time to
   * @param action the action to run
   * @param <T> the action's result type
   * @return the action's result
   */
  public static <T> T time(final Phase phase, final Supplier<T> action) {
    final BlockImportTimings timings = CURRENT.get();
    if (timings == null) {
      return action.get();
    }
    final long start = System.nanoTime();
    try {
      return action.get();
    } finally {
      timings.add(phase, System.nanoTime() - start);
    }
  }

  /**
   * Runs the action and attributes its duration to the phase when an import is being timed on this
   * thread.
   *
   * @param phase the phase to attribute the time to
   * @param action the action to run
   */
  public static void time(final Phase phase, final Runnable action) {
    final BlockImportTimings timings = CURRENT.get();
    if (timings == null) {
      action.run();
      return;
    }
    final long start = System.nanoTime();
    try {
      action.run();
    } finally {
      timings.add(phase, System.nanoTime() - start);
    }
  }

  /**
   * Attributes the time since the given {@link System#nanoTime()} reading to the phase when an
   * import is being timed on this thread.
   *
   * @param phase the phase to attribute the time to
   * @param startNanos the reading taken when the phase started
   */
  public static void addSince(final Phase phase, final long startNanos) {
    final BlockImportTimings timings = CURRENT.get();
    if (timings != null) {
      timings.add(phase, System.nanoTime() - startNanos);
    }
  }

  /**
   * Counts an occurrence of a count-only phase when an import is being timed on this thread.
   *
   * @param phase the phase to count
   */
  public static void mark(final Phase phase) {
    final BlockImportTimings timings = CURRENT.get();
    if (timings != null) {
      timings.phaseCounts[phase.ordinal()]++;
    }
  }

  private void add(final Phase phase, final long nanos) {
    phaseNanos[phase.ordinal()] += nanos;
    phaseCounts[phase.ordinal()]++;
  }

  /**
   * Time attributed to a phase.
   *
   * @param phase the phase
   * @return the accumulated nanoseconds
   */
  public long nanos(final Phase phase) {
    return phaseNanos[phase.ordinal()];
  }

  /**
   * Number of times a phase was entered.
   *
   * @param phase the phase
   * @return the count
   */
  public int count(final Phase phase) {
    return phaseCounts[phase.ordinal()];
  }

  /**
   * Wall time from {@link #begin()} to {@link #finish()}.
   *
   * @return the nanoseconds
   */
  public long totalNanos() {
    return totalNanos;
  }

  /**
   * CPU time consumed by the importing thread between {@link #begin()} and {@link #finish()}.
   *
   * @return the nanoseconds
   */
  public long cpuNanos() {
    return cpuNanos;
  }

  /**
   * JVM garbage collection time that elapsed between {@link #begin()} and {@link #finish()}.
   *
   * @return the nanoseconds, at millisecond resolution
   */
  public long gcNanos() {
    return gcNanos;
  }

  /**
   * Wall time the thread spent neither on CPU nor stopped for collection.
   *
   * @return the nanoseconds, never negative
   */
  public long remainderNanos() {
    return Math.max(0L, totalNanos - cpuNanos - gcNanos);
  }

  /**
   * Formats the phases that were entered as a log fragment, in milliseconds.
   *
   * @return the description
   */
  public String describe() {
    final StringBuilder out = new StringBuilder();
    for (final Phase phase : Phase.values()) {
      final int count = phaseCounts[phase.ordinal()];
      if (count == 0) {
        continue;
      }
      out.append(phase.label());
      if (!phase.isCountOnly()) {
        out.append(' ').append(millis(phaseNanos[phase.ordinal()]));
      }
      if (count > 1 || phase.isCountOnly()) {
        out.append(" (").append(count).append(')');
      }
      out.append(" | ");
    }
    out.append(TOTAL)
        .append(' ')
        .append(millis(totalNanos))
        .append(" | ")
        .append(CPU)
        .append(' ')
        .append(millis(cpuNanos))
        .append(" | ")
        .append(GC)
        .append(' ')
        .append(millis(gcNanos))
        .append(" | ")
        .append(REMAINDER)
        .append(' ')
        .append(millis(remainderNanos()));
    return out.toString();
  }

  /**
   * Logs the breakdown at info level on this class's logger, so it can be silenced on its own.
   *
   * @param kind what was timed, such as an import or a fork choice update
   * @param blockNumber the block the timing belongs to
   */
  public void log(final String kind, final long blockNumber) {
    if (LOG.isInfoEnabled()) {
      LOG.info("{} #{} | {}", kind, blockNumber, describe());
    }
  }

  /**
   * Records every entered timed phase, the total, the CPU time, the GC time and the remainder into
   * the histogram, labelled by phase. Count-only phases are not durations and are left out.
   *
   * @param histogram the histogram to observe into, in seconds
   */
  public void recordTo(final LabelledMetric<Histogram> histogram) {
    for (final Phase phase : Phase.values()) {
      if (phaseCounts[phase.ordinal()] > 0 && !phase.isCountOnly()) {
        histogram.labels(phase.label()).observe(seconds(phaseNanos[phase.ordinal()]));
      }
    }
    histogram.labels(TOTAL).observe(seconds(totalNanos));
    histogram.labels(CPU).observe(seconds(cpuNanos));
    histogram.labels(GC).observe(seconds(gcNanos));
    histogram.labels(REMAINDER).observe(seconds(remainderNanos()));
  }

  /**
   * Creates the histogram that {@link #recordTo(LabelledMetric)} observes into. The metrics system
   * returns the same instance for repeated calls with this name.
   *
   * @param metricsSystem the metrics system
   * @return the histogram, labelled by phase
   */
  public static LabelledMetric<Histogram> createHistogram(final MetricsSystem metricsSystem) {
    return metricsSystem.createLabelledHistogram(
        BesuMetricCategory.BLOCK_PROCESSING,
        "import_phase_seconds",
        "Wall time of each phase of a block import on the importing thread",
        new double[] {0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0},
        "phase");
  }

  private static long currentThreadCpuNanos() {
    return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : 0L;
  }

  private static long collectionMillis() {
    long millis = 0L;
    for (final GarbageCollectorMXBean collector : COLLECTORS) {
      final long time = collector.getCollectionTime();
      if (time > 0) {
        millis += time;
      }
    }
    return millis;
  }

  private static String millis(final long nanos) {
    return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
  }

  private static double seconds(final long nanos) {
    return nanos / 1_000_000_000.0;
  }
}
