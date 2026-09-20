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
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.GenesisState;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.MainnetProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueStorageProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.code.BonsaiCodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.provider.BonsaiWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.preload.BonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.codestore.CodeRoutingKeyValueStorage.DelegateCodeMode;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;
import org.hyperledger.besu.services.kvstore.InMemoryKeyValueStorage;
import org.hyperledger.besu.services.kvstore.SegmentedInMemoryKeyValueStorage;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real SELFDESTRUCT transactions through the EVM on a Bonsai world state whose code lives in
 * the mmap store, to pin down whether Bonsai ever asks the store to delete code.
 */
class SelfDestructTest {

  private static final BigInteger CHAIN_ID = BigInteger.valueOf(1337);
  private static final KeyPair SENDER_KEYS =
      SignatureAlgorithmFactory.getInstance()
          .createKeyPair(
              SignatureAlgorithmFactory.getInstance()
                  .createPrivateKey(
                      Bytes32.fromHexString(
                          "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63")));
  private static final Address SENDER = Address.extract(SENDER_KEYS.getPublicKey());
  private static final Address FACTORY =
      Address.fromHexString("0xfac7000000000000000000000000000000000000");

  // CALLER SELFDESTRUCT, padded so the hash is this test's own.
  private static final Bytes CHILD_RUNTIME = Bytes.fromHexString("0x33ff00c0de");
  private static final Hash CHILD_HASH = Hash.hash(CHILD_RUNTIME);
  // PUSH5 runtime, PUSH1 0, MSTORE, PUSH1 5, PUSH1 27, RETURN
  private static final Bytes CHILD_INITCODE =
      Bytes.fromHexString("0x64" + "33ff00c0de" + "600052" + "6005" + "601b" + "f3");
  // Deploys the child with CREATE, then calls it, so it is created and destroyed in one
  // transaction: PUSH14 initcode, PUSH1 0, MSTORE, CREATE(0, 18, 14), CALL(gas, child, 0...).
  private static final Bytes FACTORY_RUNTIME =
      Bytes.fromHexString(
          "0x6d"
              + CHILD_INITCODE.toUnprefixedHexString()
              + "600052"
              + "600e6012"
              + "6000f0"
              + "60006000600060006000"
              + "855af100");

  @TempDir Path dir;

  private CodeStore store;
  private SegmentedInMemoryKeyValueStorage delegate;
  private ProtocolSchedule schedule;
  private MutableWorldState worldState;
  private long nonce;
  private long blockNumber;

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  private void start(final String forkConfig) throws Exception {
    final GenesisConfig genesis =
        GenesisConfig.fromConfig(
            "{\"config\":{\"chainId\":1337,\"londonBlock\":0,\"terminalTotalDifficulty\":0,"
                + forkConfig
                + "},\"gasLimit\":\"0x1c9c380\",\"difficulty\":\"0x0\",\"baseFeePerGas\":\"0x7\","
                + "\"alloc\":{}}");
    schedule =
        MainnetProtocolSchedule.fromConfig(
            genesis.getConfigOptions(),
            Optional.empty(),
            Optional.empty(),
            MiningConfiguration.newDefault(),
            new BadBlockManager(),
            false,
            BalConfiguration.DEFAULT,
            new NoOpMetricsSystem());

    store = CodeStore.open(dir, CodeStoreOptions.defaults().withLogGrowStep(1L << 20));
    delegate = new SegmentedInMemoryKeyValueStorage();
    final CodeRoutingKeyValueStorage routing =
        new CodeRoutingKeyValueStorage(
            delegate, new MmapCodeKeyValueStorage(store), DelegateCodeMode.VERIFY);
    final KeyValueStorageProvider provider =
        new KeyValueStorageProvider(
            segments -> routing, new InMemoryKeyValueStorage(), new NoOpMetricsSystem());
    final BonsaiCodeCache codeCache = new BonsaiCodeCache();
    final BonsaiWorldStateProvider archive =
        new BonsaiWorldStateProvider(
            (BonsaiWorldStateKeyValueStorage)
                provider.createWorldStateStorage(DataStorageConfiguration.DEFAULT_BONSAI_CONFIG),
            InMemoryKeyValueStorageProvider.createInMemoryBlockchain(
                GenesisState.fromConfig(genesis, schedule, codeCache).getBlock()),
            DataStorageConfiguration.DEFAULT_BONSAI_CONFIG.getExtraStorageConfiguration(),
            new BonsaiCachedMerkleTrieLoader(new NoOpMetricsSystem()),
            null,
            EvmConfiguration.DEFAULT,
            codeCache);
    worldState = archive.getWorldState();

    final WorldUpdater updater = worldState.updater();
    updater.createAccount(SENDER).setBalance(Wei.fromEth(100));
    final MutableAccount factory = updater.createAccount(FACTORY);
    factory.setNonce(1);
    factory.setCode(FACTORY_RUNTIME);
    updater.commit();
    worldState.persist(null);
  }

  /** Runs one transaction as a block of its own and persists the result. */
  private void runBlock(final Optional<Address> to, final Bytes payload) {
    final BlockHeader header =
        new BlockHeaderTestFixture()
            .number(++blockNumber)
            .timestamp(blockNumber * 12)
            .gasLimit(30_000_000L)
            .baseFeePerGas(Wei.of(7))
            .difficulty(org.hyperledger.besu.ethereum.core.Difficulty.ZERO)
            .prevRandao(Bytes32.ZERO)
            .buildHeader();
    final Transaction.Builder tx =
        Transaction.builder()
            .type(org.hyperledger.besu.datatypes.TransactionType.FRONTIER)
            .nonce(nonce++)
            .gasPrice(Wei.of(10))
            .gasLimit(1_000_000L)
            .value(Wei.ZERO)
            .payload(payload)
            .chainId(CHAIN_ID);
    to.ifPresent(tx::to);

    final WorldUpdater updater = worldState.updater();
    final TransactionProcessingResult result =
        schedule
            .getByBlockHeader(header)
            .getTransactionProcessor()
            .processTransaction(
                updater,
                header,
                tx.signAndBuild(SENDER_KEYS),
                Address.ZERO,
                (frame, number) -> Hash.ZERO,
                TransactionValidationParams.processingBlock(),
                Wei.ZERO);
    assertThat(result.isSuccessful()).as(result.toString()).isTrue();
    updater.commit();
    worldState.persist(null);
  }

  private boolean storeHoldsChildCode() {
    return store.contains(CHILD_HASH.getBytes().toArrayUnsafe());
  }

  private boolean delegateHoldsChildCode() {
    return delegate.get(CODE_STORAGE, CHILD_HASH.getBytes().toArrayUnsafe()).isPresent();
  }

  @Test
  void codeCreatedAndDestroyedInOneTransactionNeverReachesTheStore() throws Exception {
    start("\"shanghaiTime\":0,\"cancunTime\":0");
    final Address child = Address.contractAddress(FACTORY, 1);

    runBlock(Optional.of(FACTORY), Bytes.EMPTY);

    assertThat(worldState.get(FACTORY).getNonce()).isEqualTo(2);
    assertThat(worldState.get(child)).isNull();
    assertThat(storeHoldsChildCode()).isFalse();
    assertThat(delegateHoldsChildCode()).isFalse();
  }

  @Test
  void selfDestructInALaterTransactionKeepsTheAccountAndItsCode() throws Exception {
    start("\"shanghaiTime\":0,\"cancunTime\":0");
    final Address child = Address.contractAddress(SENDER, 0);

    runBlock(Optional.empty(), CHILD_INITCODE);
    assertThat(storeHoldsChildCode()).isTrue();

    runBlock(Optional.of(child), Bytes.EMPTY);

    final Account account = worldState.get(child);
    assertThat(account).isNotNull();
    assertThat(account.getCode()).isEqualTo(CHILD_RUNTIME);
    assertThat(storeHoldsChildCode()).isTrue();
  }

  @Test
  void deletingAnAccountBeforeCancunLeavesItsCodeInTheStore() throws Exception {
    start("\"shanghaiTime\":0");
    final Address child = Address.contractAddress(SENDER, 0);

    runBlock(Optional.empty(), CHILD_INITCODE);
    runBlock(Optional.of(child), Bytes.EMPTY);

    assertThat(worldState.get(child)).isNull();
    assertThat(storeHoldsChildCode()).isTrue();
    assertThat(delegateHoldsChildCode()).isTrue();

    // The same code deployed again is served from the record that was never removed.
    final Address second = Address.contractAddress(SENDER, 2);
    final long entries = store.entries();
    runBlock(Optional.empty(), CHILD_INITCODE);
    assertThat(worldState.get(second).getCode()).isEqualTo(CHILD_RUNTIME);
    assertThat(store.entries()).isEqualTo(entries);
  }
}
