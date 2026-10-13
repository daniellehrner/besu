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
package org.hyperledger.besu.plugin.services.storage.rocksdb.segmented;

import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.SegmentBulkLoad;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.MutableColumnFamilyOptions;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switches the automatic compaction of column families off for the time of a bulk load.
 *
 * <p>A sorted file that cannot go to the bottom level, because its key range holds other keys or
 * overlaps another file, is placed higher up. With automatic compaction on, RocksDB starts merging
 * such files down while the load is still adding files. A running compaction keeps every file that
 * overlaps its output out of the levels below, so those files pile up in level 0, the next
 * compaction has more to merge, and the entries end up being written many times over, which is what
 * the sorted files were meant to avoid. With it off the files stay where they were placed.
 *
 * <p>When the load is closed the column families go back to compacting on their own. Nothing is
 * compacted by hand: a manual compaction of a whole column family merges every level into the next
 * and so rewrites the bottom level, which holds nearly all of the load, for the sake of the few
 * files above it.
 */
final class RocksDBSegmentBulkLoad implements SegmentBulkLoad {

  private static final Logger LOG = LoggerFactory.getLogger(RocksDBSegmentBulkLoad.class);

  private static final String LEVEL0_FILES_PROPERTY = "rocksdb.num-files-at-level0";
  private static final int NO_LEVEL0_LIMIT = Integer.MAX_VALUE;
  private static final long LEVEL0_POLL_MILLIS = 500;
  private static final long LEVEL0_WAIT_NANOS = TimeUnit.MINUTES.toNanos(30);

  private final RocksDBColumnarKeyValueStorage storage;
  private final List<SegmentIdentifier> segments;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  RocksDBSegmentBulkLoad(
      final RocksDBColumnarKeyValueStorage storage, final List<SegmentIdentifier> segments) {
    this.storage = storage;
    this.segments = List.copyOf(segments);
    for (final SegmentIdentifier segment : this.segments) {
      setOptions(segment, MutableColumnFamilyOptions.builder().setDisableAutoCompactions(true));
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    for (final SegmentIdentifier segment : segments) {
      if (storage.isClosed()) {
        // the column families come back with automatic compaction on when the database is opened
        return;
      }
      try {
        resumeCompaction(segment);
      } catch (final RuntimeException e) {
        if (storage.isClosed()) {
          return;
        }
        // the other segments still have to get theirs back
        LOG.warn(
            "Automatic compaction of the {} segment could not be switched back on after its bulk load, it stays off until the database is opened again",
            segment.getName(),
            e);
      }
    }
  }

  private void resumeCompaction(final SegmentIdentifier segment) {
    final ColumnFamilyOptions configured = storage.columnFamilyOptions(segment);
    final int slowdownTrigger = configured.level0SlowdownWritesTrigger();
    final int stopTrigger = configured.level0StopWritesTrigger();
    LOG.info("Bulk load of the {} segment ended with {}", segment.getName(), levels(segment));
    if (level0Files(segment) < slowdownTrigger) {
      setOptions(segment, MutableColumnFamilyOptions.builder().setDisableAutoCompactions(false));
      return;
    }
    // RocksDB does not hold back writes for the number of files in level 0 while automatic
    // compaction is off, but does so the moment it is switched on: with this many files it would
    // slow down or stop every write to the database, not only the ones to this column family,
    // until it has merged them down. The limits are lifted for that time.
    final long start = System.nanoTime();
    setOptions(
        segment,
        MutableColumnFamilyOptions.builder()
            .setLevel0SlowdownWritesTrigger(NO_LEVEL0_LIMIT)
            .setLevel0StopWritesTrigger(NO_LEVEL0_LIMIT)
            .setDisableAutoCompactions(false));
    try {
      while (!storage.isClosed()
          && level0Files(segment) >= slowdownTrigger
          && System.nanoTime() - start < LEVEL0_WAIT_NANOS) {
        Thread.sleep(LEVEL0_POLL_MILLIS);
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (!storage.isClosed()) {
        setOptions(
            segment,
            MutableColumnFamilyOptions.builder()
                .setLevel0SlowdownWritesTrigger(slowdownTrigger)
                .setLevel0StopWritesTrigger(stopTrigger));
      }
    }
    LOG.info(
        "Level 0 of the {} segment was merged down {} s after its bulk load",
        segment.getName(),
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start));
  }

  private long level0Files(final SegmentIdentifier segment) {
    try {
      return Long.parseLong(
          storage.getDB().getProperty(storage.safeColumnHandle(segment), LEVEL0_FILES_PROPERTY));
    } catch (final RocksDBException e) {
      throw new StorageException(e);
    }
  }

  private String levels(final SegmentIdentifier segment) {
    return storage
        .getDB()
        .getColumnFamilyMetaData(storage.safeColumnHandle(segment))
        .levels()
        .stream()
        .filter(level -> !level.files().isEmpty())
        .map(
            level ->
                String.format(
                    "%d files (%d MB) in level %d",
                    level.files().size(), level.size() >> 20, level.level()))
        .collect(Collectors.joining(", "));
  }

  private void setOptions(
      final SegmentIdentifier segment,
      final MutableColumnFamilyOptions.MutableColumnFamilyOptionsBuilder options) {
    final ColumnFamilyHandle handle = storage.safeColumnHandle(segment);
    try {
      storage.getDB().setOptions(handle, options.build());
    } catch (final RocksDBException e) {
      throw new StorageException(e);
    }
  }
}
