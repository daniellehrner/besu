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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_INFO_STATE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProviderBuilder;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiSnapshotWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.code.JumpDestCodeStorageStrategy;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.ImmutableDataStorageConfiguration;
import org.hyperledger.besu.ethereum.worldstate.ImmutableExtraStorageConfiguration;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.BesuConfiguration;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.codestore.CodeRoutingKeyValueStorage.DelegateCodeMode;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBKeyValueStorageFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.RocksDBMetricsFactory;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.RocksDBFactoryConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Bonsai storage over the bonsai-mmap factory with real RocksDB underneath. */
class BonsaiMmapStorageTest {

  private static final Bytes CODE = Bytes.fromHexString("0x6001600101600055");
  private static final Hash CODE_HASH = Hash.hash(CODE);
  private static final Hash ACCOUNT = Hash.hash(Bytes.of(1));
  private static final List<SegmentIdentifier> COMPOSED =
      List.of(
          ACCOUNT_INFO_STATE,
          CODE_STORAGE,
          KeyValueSegmentIdentifier.ACCOUNT_STORAGE_STORAGE,
          KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE);

  @TempDir Path dataDir;

  private Node node;

  /** One start of a node on {@link #dataDir}. */
  private final class Node implements AutoCloseable {
    final RocksDBKeyValueStorageFactory rocksDb =
        new RocksDBKeyValueStorageFactory(
            () ->
                new RocksDBFactoryConfiguration(
                    1024, 4, 8388608, false, false, false, Optional.empty(), Optional.empty()),
            Arrays.asList(KeyValueSegmentIdentifier.values()),
            RocksDBMetricsFactory.PUBLIC_ROCKS_DB_METRICS);
    final KeyValueStorageFactory factory;
    final KeyValueStorageProvider provider;
    final BesuConfiguration configuration = mock(BesuConfiguration.class);

    /** A null mode starts the node on plain RocksDB. */
    Node(final DelegateCodeMode mode, final boolean dropDelegateCode) {
      final org.hyperledger.besu.plugin.services.storage.DataStorageConfiguration storage =
          mock(org.hyperledger.besu.plugin.services.storage.DataStorageConfiguration.class);
      when(storage.getDatabaseFormat()).thenReturn(DataStorageFormat.BONSAI);
      when(configuration.getDataStorageConfiguration()).thenReturn(storage);
      when(configuration.getDatabaseFormat()).thenReturn(DataStorageFormat.BONSAI);
      when(configuration.getDataPath()).thenReturn(dataDir);
      when(configuration.getStoragePath()).thenReturn(dataDir.resolve("database"));
      factory =
          mode == null
              ? rocksDb
              : new DelegatingKeyValueStorageFactory(() -> rocksDb, mode, dropDelegateCode);
      provider =
          new KeyValueStorageProviderBuilder()
              .withStorageFactory(factory)
              .withCommonConfiguration(configuration)
              .withMetricsSystem(new NoOpMetricsSystem())
              .build();
    }

    BonsaiWorldStateKeyValueStorage bonsai(final DataStorageConfiguration config) {
      return new BonsaiWorldStateKeyValueStorage(provider, new NoOpMetricsSystem(), config);
    }

    BonsaiWorldStateKeyValueStorage bonsai() {
      return bonsai(DataStorageConfiguration.DEFAULT_BONSAI_CONFIG);
    }

    SegmentedKeyValueStorage rocksDbStorage() {
      return rocksDb.create(COMPOSED, configuration, new NoOpMetricsSystem());
    }

    Optional<byte[]> codeInRocksDb() {
      return rocksDbStorage().get(CODE_STORAGE, CODE_HASH.getBytes().toArrayUnsafe());
    }

    @Override
    public void close() throws Exception {
      provider.close();
      factory.close();
      rocksDb.close();
    }
  }

  private Node start(final DelegateCodeMode mode) throws Exception {
    return start(mode, false);
  }

  private Node start(final DelegateCodeMode mode, final boolean dropDelegateCode) throws Exception {
    if (node != null) {
      node.close();
    }
    node = new Node(mode, dropDelegateCode);
    return node;
  }

  @AfterEach
  void stop() throws Exception {
    if (node != null) {
      node.close();
    }
  }

  private static Optional<Bytes> codeOf(final BonsaiWorldStateKeyValueStorage bonsai) {
    return bonsai.getCode(CODE_HASH, ACCOUNT).map(Code::getBytes);
  }

  private static Optional<Bytes> codeOf(final BonsaiSnapshotWorldStateKeyValueStorage snapshot) {
    return snapshot.getCode(CODE_HASH, ACCOUNT).map(Code::getBytes);
  }

  private static void deployCode(final BonsaiWorldStateKeyValueStorage bonsai) {
    final BonsaiWorldStateKeyValueStorage.Updater updater = bonsai.updater();
    updater.putCode(ACCOUNT, CODE_HASH, CODE);
    updater.commit();
  }

  @Test
  void bonsaiCodeLivesInTheMmapStore() throws Exception {
    final BonsaiWorldStateKeyValueStorage bonsai = start(DelegateCodeMode.IGNORE).bonsai();
    deployCode(bonsai);

    assertThat(codeOf(bonsai)).contains(CODE);
    assertThat(node.codeInRocksDb()).isEmpty();
    assertThat(Files.size(dataDir.resolve("code-store").resolve(CodeLog.FILE_NAME)))
        .isGreaterThan(CodeLog.HEADER_SIZE);

    // Survives a restart, and Bonsai's keying sniff reads the mmap store.
    final BonsaiWorldStateKeyValueStorage restarted = start(DelegateCodeMode.IGNORE).bonsai();
    assertThat(codeOf(restarted)).contains(CODE);
    assertThat(restarted.getFlatDbStrategy().isCodeByCodeHash()).isTrue();
  }

  @Test
  void aNonEmptyStoreForcesCodeHashKeyingWhateverTheFlagSays() throws Exception {
    deployCode(start(DelegateCodeMode.IGNORE).bonsai());
    final BonsaiWorldStateKeyValueStorage restarted =
        start(DelegateCodeMode.IGNORE).bonsai(accountHashKeying());
    assertThat(restarted.getFlatDbStrategy().isCodeByCodeHash()).isTrue();
  }

  @Test
  void accountHashKeyingIsRefusedAndNothingIsCommitted() throws Exception {
    final BonsaiWorldStateKeyValueStorage bonsai =
        start(DelegateCodeMode.MIRROR).bonsai(accountHashKeying());
    assertThat(bonsai.getFlatDbStrategy().isCodeByCodeHash()).isFalse();

    final BonsaiWorldStateKeyValueStorage.Updater updater = bonsai.updater();
    updater.putCode(ACCOUNT, CODE_HASH, CODE);
    updater.putAccountInfoState(ACCOUNT, Bytes.of(7));
    assertThatThrownBy(updater::commit)
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("not its keccak hash");

    assertThat(node.rocksDbStorage().get(ACCOUNT_INFO_STATE, ACCOUNT.getBytes().toArrayUnsafe()))
        .isEmpty();
    assertThat(node.rocksDbStorage().streamKeys(CODE_STORAGE).filter(k -> k.length == 32))
        .isEmpty();
  }

  @Test
  void removingCodeIsANoOpUnderCodeHashKeying() throws Exception {
    final BonsaiWorldStateKeyValueStorage bonsai = start(DelegateCodeMode.IGNORE).bonsai();
    deployCode(bonsai);
    final BonsaiWorldStateKeyValueStorage.Updater updater = bonsai.updater();
    updater.removeCode(ACCOUNT, CODE_HASH);
    updater.commit();
    assertThat(codeOf(bonsai)).contains(CODE);
  }

  @Test
  void snapshotsReadCode() throws Exception {
    final BonsaiWorldStateKeyValueStorage bonsai = start(DelegateCodeMode.IGNORE).bonsai();
    deployCode(bonsai);
    try (BonsaiSnapshotWorldStateKeyValueStorage snapshot =
        new BonsaiSnapshotWorldStateKeyValueStorage(bonsai)) {
      assertThat(codeOf(snapshot)).contains(CODE);

      // A snapshot's writes stay in the snapshot.
      final Bytes other = Bytes.fromHexString("0x00");
      final BonsaiWorldStateKeyValueStorage.Updater updater = snapshot.updater();
      updater.putCode(ACCOUNT, Hash.hash(other), other);
      updater.commit();
      assertThat(bonsai.getCode(Hash.hash(other), ACCOUNT)).isEmpty();
    }
  }

  @Test
  void clearRemovesCode() throws Exception {
    final BonsaiWorldStateKeyValueStorage bonsai = start(DelegateCodeMode.MIRROR).bonsai();
    deployCode(bonsai);
    bonsai.clear();
    assertThat(codeOf(bonsai)).isEmpty();
    assertThat(node.codeInRocksDb()).isEmpty();
    deployCode(bonsai);
    assertThat(codeOf(bonsai)).contains(CODE);
  }

  @Test
  void mirroringMakesFallingBackToRocksDbAOneFlagChange() throws Exception {
    deployCode(start(DelegateCodeMode.MIRROR).bonsai());
    assertThat(node.codeInRocksDb()).contains(JumpDestCodeStorageStrategy.encode(CODE));

    assertThat(codeOf(start(null).bonsai())).contains(CODE);
  }

  @Test
  void withoutMirroringRocksDbStillStartsCleanlyButLacksTheCode() throws Exception {
    deployCode(start(DelegateCodeMode.IGNORE).bonsai());
    assertThat(codeOf(start(null).bonsai())).isEmpty();
  }

  @Test
  void aDatabaseThatRanWithoutMirroringIsOnlyReachableThroughTheMmapStore() throws Exception {
    deployCode(start(DelegateCodeMode.IGNORE).bonsai());

    assertThatThrownBy(
            () -> DelegatingKeyValueStorageFactory.requireCodeReachable("rocksdb", dataDir))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("--key-value-storage=bonsai-mmap");
    DelegatingKeyValueStorageFactory.requireCodeReachable("bonsai-mmap", dataDir);
    for (final DelegateCodeMode mode : List.of(DelegateCodeMode.MIRROR, DelegateCodeMode.VERIFY)) {
      assertThatThrownBy(start(mode)::bonsai)
          .isInstanceOf(StorageException.class)
          .hasMessageContaining("has run without mirroring");
    }
  }

  @Test
  void aMirroredDatabaseStaysReachableFromRocksDb() throws Exception {
    deployCode(start(DelegateCodeMode.MIRROR).bonsai());
    DelegatingKeyValueStorageFactory.requireCodeReachable("rocksdb", dataDir);
  }

  @Test
  void dropsTheMirroredCodeOnceItIsUnused() throws Exception {
    deployCode(start(DelegateCodeMode.MIRROR).bonsai());

    final BonsaiWorldStateKeyValueStorage bonsai = start(DelegateCodeMode.IGNORE, true).bonsai();

    assertThat(node.codeInRocksDb()).isEmpty();
    assertThat(codeOf(bonsai)).contains(CODE);
  }

  @Test
  void keepsTheDelegateCodeIfTheMmapStoreLacksAnyOfIt() throws Exception {
    deployCode(start(DelegateCodeMode.MIRROR).bonsai());
    final Bytes other = Bytes.fromHexString("0x6002600201");
    final BonsaiWorldStateKeyValueStorage.Updater updater = start(null).bonsai().updater();
    updater.putCode(ACCOUNT, Hash.hash(other), other);
    updater.commit();

    assertThatThrownBy(start(DelegateCodeMode.IGNORE, true)::bonsai)
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("Not dropping the code");
    assertThat(codeOf(start(null).bonsai())).contains(CODE);
  }

  @Test
  void refusesToStartOnAnUnmigratedDatabase() throws Exception {
    deployCode(start(null).bonsai());
    final Node mmap = start(DelegateCodeMode.MIRROR);
    assertThatThrownBy(mmap::bonsai)
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("Migrate the code first");
  }

  @Test
  void verifyModeFailsOnADifferentialReadMismatch() throws Exception {
    final BonsaiWorldStateKeyValueStorage bonsai = start(DelegateCodeMode.VERIFY).bonsai();
    deployCode(bonsai);
    assertThat(codeOf(bonsai)).contains(CODE);

    final SegmentedKeyValueStorageTransaction tamper = node.rocksDbStorage().startTransaction();
    tamper.put(CODE_STORAGE, CODE_HASH.getBytes().toArrayUnsafe(), new byte[] {1, 2, 3});
    tamper.commit();

    assertThatThrownBy(() -> bonsai.getCode(CODE_HASH, ACCOUNT))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("Differential read mismatch");
  }

  @Test
  void storesAnalysedCodeAndKeepsTheStrategyMarkerInRocksDb() throws Exception {
    final Node node = start(DelegateCodeMode.IGNORE);
    final BonsaiWorldStateKeyValueStorage bonsai = node.bonsai();
    deployCode(bonsai);

    assertThat(node.rocksDbStorage().get(CODE_STORAGE, JumpDestCodeStorageStrategy.MARKER_KEY))
        .contains(JumpDestCodeStorageStrategy.MARKER);
    assertThat(node.codeInRocksDb()).isEmpty();
    final Code code = bonsai.getCode(CODE_HASH, ACCOUNT).orElseThrow();
    assertThat(code.getBytes()).isEqualTo(CODE);
    assertThat(code.getJumpDestBitMask()).isNotNull();
  }

  @Test
  void aStoreHoldingBareCodeCannotBeMigratedInPlace() throws Exception {
    final Node node = start(DelegateCodeMode.IGNORE);
    try (CodeStore store = CodeStore.open(dataDir.resolve("code-store"))) {
      store.put(CODE_HASH.getBytes().toArrayUnsafe(), CODE.toArrayUnsafe());
    }
    assertThatThrownBy(node::bonsai)
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("cannot be rewritten");
  }

  private static DataStorageConfiguration accountHashKeying() {
    return ImmutableDataStorageConfiguration.builder()
        .from(DataStorageConfiguration.DEFAULT_BONSAI_CONFIG)
        .extraStorageConfiguration(
            ImmutableExtraStorageConfiguration.builder()
                .unstable(
                    ImmutableExtraStorageConfiguration.Unstable.builder()
                        .codeStoredByCodeHashEnabled(false)
                        .build())
                .build())
        .build();
  }
}
