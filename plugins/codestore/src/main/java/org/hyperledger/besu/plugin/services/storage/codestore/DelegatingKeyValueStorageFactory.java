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

import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.codestore.CodeRoutingKeyValueStorage.DelegateCodeMode;
import org.hyperledger.besu.services.kvstore.SegmentedKeyValueStorageAdapter;
import org.hyperledger.besu.util.BesuVersionUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code bonsai-mmap} storage: {@code CODE_STORAGE} on the mmap code store, everything else on
 * a delegate factory, RocksDB in production.
 *
 * <p>The delegate's files are never altered beyond what the delegate itself does, so going back is
 * a matter of selecting the delegate again. By default code is mirrored into the delegate's own
 * code segment to keep that true for code written while this storage was in use.
 */
public class DelegatingKeyValueStorageFactory implements KeyValueStorageFactory {

  /** Value of {@code --key-value-storage} that selects this factory. */
  public static final String NAME = "bonsai-mmap";

  /** Directory under the data path that holds the code store. */
  public static final String CODE_STORE_DIRECTORY = "code-store";

  /** Set to {@code true} to check every code read against the delegate. */
  public static final String VERIFY_PROPERTY = "bonsai.mmap.verify";

  /** Set to {@code false} to stop mirroring code into the delegate. */
  public static final String MIRROR_PROPERTY = "bonsai.mmap.mirror";

  /** Set to {@code false} to skip pre-faulting the code log on open. */
  public static final String PRELOAD_PROPERTY = "bonsai.mmap.preload";

  private static final Logger LOG = LoggerFactory.getLogger(DelegatingKeyValueStorageFactory.class);

  private final Supplier<KeyValueStorageFactory> delegate;
  private final DelegateCodeMode mode;
  private CodeStore codeStore;

  /**
   * Creates the factory with the mode taken from the system properties.
   *
   * @param delegate resolves the factory for every other segment; called on first use
   */
  public DelegatingKeyValueStorageFactory(final Supplier<KeyValueStorageFactory> delegate) {
    this(delegate, modeFromSystemProperties());
  }

  /**
   * Creates the factory.
   *
   * @param delegate resolves the factory for every other segment; called on first use
   * @param mode how the delegate's own code segment is used
   */
  public DelegatingKeyValueStorageFactory(
      final Supplier<KeyValueStorageFactory> delegate, final DelegateCodeMode mode) {
    this.delegate = delegate;
    this.mode = mode;
  }

  private static DelegateCodeMode modeFromSystemProperties() {
    if (Boolean.getBoolean(VERIFY_PROPERTY)) {
      return DelegateCodeMode.VERIFY;
    }
    return Boolean.parseBoolean(System.getProperty(MIRROR_PROPERTY, "true"))
        ? DelegateCodeMode.MIRROR
        : DelegateCodeMode.IGNORE;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public KeyValueStorage create(
      final SegmentIdentifier segment,
      final BesuConfiguration configuration,
      final MetricsSystem metricsSystem)
      throws StorageException {
    return new SegmentedKeyValueStorageAdapter(
        segment, create(List.of(segment), configuration, metricsSystem));
  }

  @Override
  public synchronized SegmentedKeyValueStorage create(
      final List<SegmentIdentifier> segments,
      final BesuConfiguration configuration,
      final MetricsSystem metricsSystem)
      throws StorageException {
    final SegmentedKeyValueStorage delegateStorage =
        delegate.get().create(segments, configuration, metricsSystem);
    if (segments.stream().noneMatch(CodeRoutingKeyValueStorage::isCode)) {
      return delegateStorage;
    }
    if (codeStore == null) {
      codeStore = openCodeStore(configuration, delegateStorage);
    }
    return new CodeRoutingKeyValueStorage(
        delegateStorage, new MmapCodeKeyValueStorage(codeStore), mode);
  }

  private CodeStore openCodeStore(
      final BesuConfiguration configuration, final SegmentedKeyValueStorage delegateStorage) {
    final Path dir = configuration.getDataPath().resolve(CODE_STORE_DIRECTORY);
    final CodeStore store;
    try {
      store =
          CodeStore.open(
              dir,
              CodeStoreOptions.defaults()
                  .withSourceBesuVersion(BesuVersionUtils.shortVersion())
                  .withPreload(Boolean.parseBoolean(System.getProperty(PRELOAD_PROPERTY, "true"))));
    } catch (final IOException e) {
      throw new StorageException("Failed to open the mmap code store in " + dir, e);
    }
    if (store.entries() == 0 && hasCode(delegateStorage)) {
      closeQuietly(store);
      throw new StorageException(
          String.format(
              "The mmap code store in %s is empty but the %s database already holds contract"
                  + " code. Migrate the code first, or start with --key-value-storage=%s.",
              dir, delegate.get().getName(), delegate.get().getName()));
    }
    LOG.info(
        "Serving CODE_STORAGE from the mmap code store in {}: {} entries, {} bytes, delegate code"
            + " mode {}",
        dir,
        store.entries(),
        store.logBytes(),
        mode);
    return store;
  }

  private static boolean hasCode(final SegmentedKeyValueStorage delegateStorage) {
    try (Stream<Pair<byte[], byte[]>> code =
        delegateStorage.stream(KeyValueSegmentIdentifier.CODE_STORAGE)) {
      return code.findFirst().isPresent();
    }
  }

  private static void closeQuietly(final CodeStore store) {
    try {
      store.close();
    } catch (final IOException e) {
      LOG.warn("Failed to close the mmap code store", e);
    }
  }

  @Override
  public boolean isSegmentIsolationSupported() {
    return delegate.get().isSegmentIsolationSupported();
  }

  @Override
  public boolean isSnapshotIsolationSupported() {
    return delegate.get().isSnapshotIsolationSupported();
  }

  /** Closes the code store. The delegate factory is closed by whoever registered it. */
  @Override
  public synchronized void close() throws IOException {
    if (codeStore != null) {
      codeStore.close();
      codeStore = null;
    }
  }
}
