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
package org.hyperledger.besu.plugin.services.storage.rocksdb.segmented;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBConfiguration;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBConfigurationBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OptimisticTransactionDBRocksDBColumnarKeyValueStorageTest
    extends RocksDBColumnarKeyValueStorageTest {

  @Override
  protected SegmentedKeyValueStorage createSegmentedStore() throws Exception {
    return new OptimisticRocksDBColumnarKeyValueStorage(
        new RocksDBConfigurationBuilder()
            .databaseDir(Files.createTempDirectory("segmentedStore"))
            .build(),
        Arrays.asList(TestSegment.DEFAULT, TestSegment.FOO, TestSegment.BAR),
        List.of(),
        new NoOpMetricsSystem(),
        RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);
  }

  @Override
  protected SegmentedKeyValueStorage createSegmentedStore(
      final Path path,
      final List<SegmentIdentifier> segments,
      final List<SegmentIdentifier> ignorableSegments) {
    return new OptimisticRocksDBColumnarKeyValueStorage(
        new RocksDBConfigurationBuilder().databaseDir(path).build(),
        segments,
        ignorableSegments,
        new NoOpMetricsSystem(),
        RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);
  }

  @Override
  protected SegmentedKeyValueStorage createSegmentedStore(
      final Path path,
      final MetricsSystem metricsSystem,
      final List<SegmentIdentifier> segments,
      final List<SegmentIdentifier> ignorableSegments) {
    return new OptimisticRocksDBColumnarKeyValueStorage(
        new RocksDBConfigurationBuilder().databaseDir(path).build(),
        segments,
        ignorableSegments,
        metricsSystem,
        RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);
  }

  @Test
  public void snapshotMultigetReadsStableSnapshotState() throws Exception {
    final OptimisticRocksDBColumnarKeyValueStorage store =
        (OptimisticRocksDBColumnarKeyValueStorage) createSegmentedStore();

    final SegmentedKeyValueStorageTransaction initialTx = store.startTransaction();
    initialTx.put(TestSegment.FOO, bytesOf(1), bytesOf(10));
    initialTx.commit();

    final RocksDBColumnarKeyValueSnapshot snapshot = store.takeSnapshot();
    try {
      final SegmentedKeyValueStorageTransaction updateTx = store.startTransaction();
      updateTx.put(TestSegment.FOO, bytesOf(1), bytesOf(11));
      updateTx.put(TestSegment.FOO, bytesOf(2), bytesOf(20));
      updateTx.commit();

      final List<Optional<byte[]>> values =
          snapshot.multiget(TestSegment.FOO, List.of(bytesOf(1), bytesOf(2), bytesOf(1)));

      assertThat(values).hasSize(3);
      assertThat(values.get(0)).isPresent();
      assertThat(values.get(0).get()).isEqualTo(bytesOf(10));
      assertThat(values.get(1)).isEmpty();
      assertThat(values.get(2)).isPresent();
      assertThat(values.get(2).get()).isEqualTo(bytesOf(10));
    } finally {
      snapshot.close();
      store.close();
    }
  }

  @Test
  public void shouldCompressTheWriteAheadLogWhenEnabled(@TempDir final Path path) throws Exception {
    final RocksDBConfiguration configuration =
        new RocksDBConfigurationBuilder().databaseDir(path).isWalCompressionEnabled(true).build();
    final List<SegmentIdentifier> segments = Arrays.asList(TestSegment.DEFAULT, TestSegment.FOO);
    // compresses well, and is small enough to stay in the write-ahead log until the database is
    // closed
    final byte[] value = new byte[256 * 1024];
    try (OptimisticRocksDBColumnarKeyValueStorage store =
        new OptimisticRocksDBColumnarKeyValueStorage(
            configuration,
            segments,
            List.of(),
            new NoOpMetricsSystem(),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS)) {
      assertThat(writtenOptions(path)).contains("wal_compression=kZSTD");
      // the log files are still reused, compression does not switch that off
      assertThat(writtenOptions(path)).doesNotContain("recycle_log_file_num=0");

      final SegmentedKeyValueStorageTransaction tx = store.startTransaction();
      tx.put(TestSegment.FOO, new byte[] {1}, value);
      tx.commit();

      assertThat(writeAheadLogSize(path)).isPositive().isLessThan(value.length / 10);
    }

    // what was only in the compressed log is read back when the database is opened again
    try (OptimisticRocksDBColumnarKeyValueStorage reopened =
        new OptimisticRocksDBColumnarKeyValueStorage(
            configuration,
            segments,
            List.of(),
            new NoOpMetricsSystem(),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS)) {
      assertThat(reopened.get(TestSegment.FOO, new byte[] {1})).contains(value);
    }
  }

  @Test
  public void shouldNotCompressTheWriteAheadLogByDefault(@TempDir final Path path)
      throws Exception {
    try (OptimisticRocksDBColumnarKeyValueStorage store =
        new OptimisticRocksDBColumnarKeyValueStorage(
            new RocksDBConfigurationBuilder().databaseDir(path).build(),
            Arrays.asList(TestSegment.DEFAULT, TestSegment.FOO),
            List.of(),
            new NoOpMetricsSystem(),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS)) {
      assertThat(writtenOptions(path)).contains("wal_compression=kNoCompression");
    }
  }

  private static long writeAheadLogSize(final Path databaseDir) throws IOException {
    try (Stream<Path> files = Files.list(databaseDir)) {
      long size = 0;
      for (final Path file : files.filter(f -> f.toString().endsWith(".log")).toList()) {
        size += Files.size(file);
      }
      return size;
    }
  }

  /** The options RocksDB wrote out when it opened the database. */
  private static List<String> writtenOptions(final Path databaseDir) throws IOException {
    try (Stream<Path> files = Files.list(databaseDir)) {
      final Path optionsFile =
          files
              .filter(file -> file.getFileName().toString().startsWith("OPTIONS-"))
              .max(Comparator.naturalOrder())
              .orElseThrow();
      return Files.readAllLines(optionsFile).stream().map(String::strip).toList();
    }
  }
}
