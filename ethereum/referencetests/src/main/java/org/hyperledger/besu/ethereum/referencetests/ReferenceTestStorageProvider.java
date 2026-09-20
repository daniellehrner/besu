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
package org.hyperledger.besu.ethereum.referencetests;

import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.codestore.CodeRoutingKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.codestore.CodeRoutingKeyValueStorage.DelegateCodeMode;
import org.hyperledger.besu.plugin.services.storage.codestore.CodeStore;
import org.hyperledger.besu.plugin.services.storage.codestore.CodeStoreOptions;
import org.hyperledger.besu.plugin.services.storage.codestore.MmapCodeKeyValueStorage;
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage;
import org.hyperledger.besu.services.kvstore.SegmentedInMemoryKeyValueStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The storage every reference test runs on: in memory, and with {@value #MMAP_CODE_STORE_DIR} set,
 * with code served by an mmap code store whose every read is checked against the in-memory copy.
 */
public class ReferenceTestStorageProvider extends KeyValueStorageProvider {

  /** System property naming the directory under which each test gets its own code store. */
  public static final String MMAP_CODE_STORE_DIR = "besu.reftest.mmapCodeStoreDir";

  private static final Cleaner CLEANER = Cleaner.create();
  private static final CodeStoreOptions SMALL_STORE =
      CodeStoreOptions.defaults().withLogGrowStep(1L << 20).withInitialIndexCapacity(1L << 8);

  /** Creates the provider. */
  public ReferenceTestStorageProvider() {
    super(
        segmentIdentifiers -> segmentedStorage(),
        new InMemoryKeyValueStorage(),
        new NoOpMetricsSystem());
  }

  private static SegmentedKeyValueStorage segmentedStorage() {
    final SegmentedInMemoryKeyValueStorage inMemory = new SegmentedInMemoryKeyValueStorage();
    final String root = System.getProperty(MMAP_CODE_STORE_DIR);
    if (root == null) {
      return inMemory;
    }
    try {
      final Path rootDir = Files.createDirectories(Path.of(root));
      final Path dir = Files.createTempDirectory(rootDir, "code-store");
      final CodeStore store = CodeStore.open(dir, SMALL_STORE);
      final CodeRoutingKeyValueStorage routing =
          new CodeRoutingKeyValueStorage(
              inMemory, new MmapCodeKeyValueStorage(store), DelegateCodeMode.VERIFY);
      // Tests never close their storage, and each open store holds mappings and file descriptors.
      CLEANER.register(routing, () -> discard(store, dir));
      return routing;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void discard(final CodeStore store, final Path dir) {
    try {
      store.close();
      try (Stream<Path> files = Files.walk(dir)) {
        files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
