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
package org.hyperledger.besu.plugin.services.storage.codestore;

import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;

import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.storage.DataStorageConfiguration;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBFactoryConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.mockito.Mockito;

/**
 * The {@code CODE_STORAGE} column family of a code store's entries, with the options Besu gives it,
 * as the backend to compare against.
 */
final class BenchmarkRocksDb implements AutoCloseable {

  private static final int LOAD_BATCH = 20_000;

  private final Path dir;
  private final boolean built;
  private RocksDBKeyValueStorageFactory factory;
  private SegmentedKeyValueStorage storage;

  BenchmarkRocksDb(final Path dir) throws IOException {
    this.dir = dir;
    // opening the column family creates its files, so this has to be settled before that
    this.built = Files.isDirectory(dir) && sstFiles() > 0;
    open();
  }

  SegmentedKeyValueStorage storage() {
    return storage;
  }

  boolean isBuilt() {
    return built;
  }

  /** Drops the block cache and the memtables the way only a restart can. */
  void reopen() {
    close();
    open();
  }

  private void open() {
    final BesuConfiguration configuration = Mockito.mock(BesuConfiguration.class);
    Mockito.when(configuration.getStoragePath()).thenReturn(dir);
    Mockito.when(configuration.getDataPath()).thenReturn(dir);
    Mockito.when(configuration.getDatabaseFormat()).thenReturn(DataStorageFormat.BONSAI);
    final DataStorageConfiguration storageConfiguration =
        Mockito.mock(DataStorageConfiguration.class);
    Mockito.when(storageConfiguration.getDatabaseFormat()).thenReturn(DataStorageFormat.BONSAI);
    Mockito.when(configuration.getDataStorageConfiguration()).thenReturn(storageConfiguration);
    factory =
        new RocksDBKeyValueStorageFactory(
            () ->
                new RocksDBFactoryConfiguration(
                    1024, 4, 134_217_728L, true, false, false, Optional.empty(), Optional.empty()),
            Arrays.asList(KeyValueSegmentIdentifier.values()),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);
    storage = factory.create(List.of(CODE_STORAGE), configuration, new NoOpMetricsSystem());
  }

  /** Writes every entry of {@code store} and waits for the background jobs to settle. */
  void fillFrom(final CodeStore store) throws InterruptedException, IOException {
    System.out.printf("Building RocksDB CODE_STORAGE in %s%n", dir);
    SegmentedKeyValueStorageTransaction transaction = storage.startTransaction();
    int inBatch = 0;
    try (Stream<Map.Entry<byte[], byte[]>> entries = store.stream()) {
      for (final Map.Entry<byte[], byte[]> entry :
          (Iterable<Map.Entry<byte[], byte[]>>) entries::iterator) {
        transaction.put(CODE_STORAGE, entry.getKey(), entry.getValue());
        if (++inBatch == LOAD_BATCH) {
          transaction.commit();
          transaction = storage.startTransaction();
          inBatch = 0;
        }
      }
    }
    transaction.commit();
    // A node's column family has been compacted over time; let the background jobs finish.
    long files = -1;
    for (long now = sstFiles(); now != files; now = sstFiles()) {
      files = now;
      Thread.sleep(30_000);
    }
    reopen();
    System.out.printf("RocksDB built: %d sst files%n", files);
  }

  private long sstFiles() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(file -> file.toString().endsWith(".sst")).count();
    }
  }

  @Override
  public void close() {
    try {
      storage.close();
      factory.close();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
