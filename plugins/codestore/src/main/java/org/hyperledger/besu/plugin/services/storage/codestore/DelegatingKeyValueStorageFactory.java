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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
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

  /**
   * Set to {@code true} to pre-fault the code log on open. Off by default: on a log that is large
   * next to the machine's memory it evicts pages the rest of the node is using.
   */
  public static final String PRELOAD_PROPERTY = "bonsai.mmap.preload";

  /**
   * System property that, when true, removes the delegate's own copy of the code once every row of
   * it is known to be in the mmap code store.
   */
  public static final String DROP_DELEGATE_CODE_PROPERTY = "bonsai.mmap.drop-delegate-code";

  /** Present in the code store directory once the delegate no longer receives every code write. */
  static final String SOLE_COPY_MARKER = "SOLE_COPY";

  private static final Logger LOG = LoggerFactory.getLogger(DelegatingKeyValueStorageFactory.class);

  private final Supplier<KeyValueStorageFactory> delegate;
  private final DelegateCodeMode mode;
  private final boolean dropDelegateCode;
  private CodeStore codeStore;

  /**
   * Creates the factory with the delegate code mode taken from the system properties.
   *
   * @param delegate supplies the factory serving every segment except code
   */
  public DelegatingKeyValueStorageFactory(final Supplier<KeyValueStorageFactory> delegate) {
    this(delegate, modeFromSystemProperties(), Boolean.getBoolean(DROP_DELEGATE_CODE_PROPERTY));
  }

  /**
   * Creates the factory.
   *
   * @param delegate supplies the factory serving every segment except code
   * @param mode how the delegate's own code segment is used
   * @param dropDelegateCode whether to remove the delegate's copy of the code when it is unused
   */
  public DelegatingKeyValueStorageFactory(
      final Supplier<KeyValueStorageFactory> delegate,
      final DelegateCodeMode mode,
      final boolean dropDelegateCode) {
    this.delegate = delegate;
    this.mode = mode;
    this.dropDelegateCode = dropDelegateCode;
  }

  private static DelegateCodeMode modeFromSystemProperties() {
    if (Boolean.getBoolean(VERIFY_PROPERTY)) {
      return DelegateCodeMode.VERIFY;
    }
    return Boolean.getBoolean(MIRROR_PROPERTY) ? DelegateCodeMode.MIRROR : DelegateCodeMode.IGNORE;
  }

  /**
   * Refuses a storage that cannot reach the code of the database in {@code dataPath}, because that
   * code was only ever written to the mmap code store.
   *
   * @param storageName the key-value storage Besu was asked to start with
   * @param dataPath the Besu data directory
   * @throws StorageException if only {@value #NAME} can serve this database's code
   */
  public static void requireCodeReachable(final String storageName, final Path dataPath) {
    final Path dir = dataPath.resolve(CODE_STORE_DIRECTORY);
    if (!NAME.equals(storageName) && Files.exists(dir.resolve(SOLE_COPY_MARKER))) {
      throw new StorageException(
          String.format(
              "The contract code of this database lives only in the mmap code store in %s."
                  + " Start with --key-value-storage=%s.",
              dir, NAME));
    }
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
                  .withPreload(
                      Boolean.parseBoolean(System.getProperty(PRELOAD_PROPERTY, "false"))));
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
    try {
      reconcileWithDelegate(store, dir, delegateStorage);
    } catch (final IOException | RuntimeException e) {
      closeQuietly(store);
      throw e instanceof StorageException se
          ? se
          : new StorageException("Failed to prepare the mmap code store in " + dir, e);
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

  private void reconcileWithDelegate(
      final CodeStore store, final Path dir, final SegmentedKeyValueStorage delegateStorage)
      throws IOException {
    final Path marker = dir.resolve(SOLE_COPY_MARKER);
    if (mode != DelegateCodeMode.IGNORE) {
      if (Files.exists(marker)) {
        throw new StorageException(
            String.format(
                "%s and %s need a %s database holding all code, but this one has run without"
                    + " mirroring.",
                MIRROR_PROPERTY, VERIFY_PROPERTY, delegate.get().getName()));
      }
      return;
    }
    final boolean drop = dropDelegateCode && hasCode(delegateStorage);
    final long rows = drop ? countDelegateCodeHeldBy(store, delegateStorage) : 0;
    // The marker has to be durable before the delegate misses any code.
    if (Files.notExists(marker)) {
      Durable.create(marker);
    }
    if (drop) {
      delegateStorage.clear(KeyValueSegmentIdentifier.CODE_STORAGE);
      LOG.info(
          "Dropped {} code rows from {}, all of them present in the mmap code store",
          rows,
          delegate.get().getName());
    }
  }

  private long countDelegateCodeHeldBy(
      final CodeStore store, final SegmentedKeyValueStorage delegateStorage) {
    try (Stream<byte[]> keys = delegateStorage.streamKeys(KeyValueSegmentIdentifier.CODE_STORAGE)) {
      final long[] rows = new long[1];
      keys.forEach(
          key -> {
            if (key.length != CodeStore.HASH_SIZE || !store.contains(key)) {
              throw new StorageException(
                  String.format(
                      "Not dropping the code held by %s: its row %s is not in the mmap code"
                          + " store.",
                      delegate.get().getName(), Bytes.wrap(key).toHexString()));
            }
            rows[0]++;
          });
      return rows[0];
    }
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
