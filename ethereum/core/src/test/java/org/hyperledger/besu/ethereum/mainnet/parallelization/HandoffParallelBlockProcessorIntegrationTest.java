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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.mainnet.parallelization.ParallelBlockProcessorTestSupport.GENESIS_CONFIG;
import static org.hyperledger.besu.ethereum.mainnet.parallelization.ParallelBlockProcessorTestSupport.PARALLEL_TEST_CONTRACT;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.core.ExecutionContextTestFixture;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Util;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ImmutableBalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecAdapters;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link HandoffTransactionProcessor}, which processes EVM v2 blocks without
 * a block access list: the blocks run before Amsterdam, and each is checked against sequential
 * processing. The refreshes run either on their worker, whenever it gets to them, or before each
 * transaction, so that every invalidated result is refreshed on the writes before it.
 */
class HandoffParallelBlockProcessorIntegrationTest {

  private static final BalConfiguration CONFIG =
      ImmutableBalConfiguration.builder().isPerfectParallelizationEnabled(false).build();

  /** Funded senders besides the genesis accounts, so that a block can have many senders. */
  static final List<KeyPair> SENDERS = new ArrayList<>();

  static {
    for (int i = 1; i <= 6; i++) {
      SENDERS.add(
          ParallelBlockProcessorTestSupport.generateKeyPair(
              String.format("%064x", BigInteger.valueOf(0x7e57_0000L + i))));
    }
  }

  /** The integration test genesis before Amsterdam, with the extra senders funded. */
  static ExecutionContextTestFixture createOsakaContextOnEvmV2() {
    final ObjectNode genesis;
    try (InputStream in =
        HandoffParallelBlockProcessorIntegrationTest.class.getResourceAsStream(GENESIS_CONFIG)) {
      genesis = (ObjectNode) new ObjectMapper().readTree(in);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
    ((ObjectNode) genesis.get("config")).remove("amsterdamTime");
    final ObjectNode alloc = (ObjectNode) genesis.get("alloc");
    for (final KeyPair sender : SENDERS) {
      final ObjectNode account =
          alloc.putObject(Util.publicKeyToAddress(sender.getPublicKey()).toHexString());
      account.put("balance", "0x3635c9adc5dea00000");
    }
    final GenesisConfig genesisConfig = GenesisConfig.fromConfig(genesis);
    return ExecutionContextTestFixture.builder(genesisConfig)
        .dataStorageFormat(DataStorageFormat.BONSAI)
        .protocolSchedule(
            new ProtocolScheduleBuilder(
                    genesisConfig.getConfigOptions(),
                    Optional.of(BigInteger.valueOf(42)),
                    ProtocolSpecAdapters.create(0, Function.identity()),
                    false,
                    new EvmConfiguration(
                        32_000L, EvmConfiguration.WorldUpdaterMode.STACKED, true, true),
                    MiningConfiguration.MINING_DISABLED,
                    new BadBlockManager(),
                    false,
                    ImmutableBalConfiguration.builder().build(),
                    new NoOpMetricsSystem())
                .createProtocolSchedule())
        .build();
  }

  @AfterEach
  void refreshOnWorker() {
    HandoffTransactionProcessor.refreshSynchronously(false);
  }

  @Nested
  @DisplayName("Simple Transfers, refreshed on the worker")
  class SimpleTransfers extends AbstractSimpleTransferTest {
    @Override
    protected String getVariantName() {
      return "Handoff (EVM v2, before Amsterdam)";
    }

    @Override
    protected BalConfiguration getBalConfiguration() {
      return CONFIG;
    }

    @Override
    protected boolean expectsBlockAccessList() {
      return false;
    }

    @Override
    protected ExecutionContextTestFixture createFreshContext() {
      return createOsakaContextOnEvmV2();
    }
  }

  @Nested
  @DisplayName("Contract Storage, refreshed on the worker")
  class ContractStorage extends AbstractContractStorageTest {
    @Override
    protected String getVariantName() {
      return "Handoff (EVM v2, before Amsterdam)";
    }

    @Override
    protected BalConfiguration getBalConfiguration() {
      return CONFIG;
    }

    @Override
    protected boolean expectsBlockAccessList() {
      return false;
    }

    @Override
    protected ExecutionContextTestFixture createFreshContext() {
      return createOsakaContextOnEvmV2();
    }
  }

  @Nested
  @DisplayName("Storage Dependency, refreshed on the worker")
  class StorageDependency extends AbstractStorageDependencyTest {
    @Override
    protected String getVariantName() {
      return "Handoff (EVM v2, before Amsterdam)";
    }

    @Override
    protected BalConfiguration getBalConfiguration() {
      return CONFIG;
    }

    @Override
    protected boolean expectsBlockAccessList() {
      return false;
    }

    @Override
    protected ExecutionContextTestFixture createFreshContext() {
      return createOsakaContextOnEvmV2();
    }
  }

  @Nested
  @DisplayName("Contract Storage, refreshed before each transaction")
  class ContractStorageRefreshedInOrder extends AbstractContractStorageTest {
    @BeforeEach
    void refreshInOrder() {
      HandoffTransactionProcessor.refreshSynchronously(true);
    }

    @Override
    protected String getVariantName() {
      return "Handoff (EVM v2, before Amsterdam, in-order refreshes)";
    }

    @Override
    protected BalConfiguration getBalConfiguration() {
      return CONFIG;
    }

    @Override
    protected boolean expectsBlockAccessList() {
      return false;
    }

    @Override
    protected ExecutionContextTestFixture createFreshContext() {
      return createOsakaContextOnEvmV2();
    }
  }

  @Nested
  @DisplayName("Storage Dependency, refreshed before each transaction")
  class StorageDependencyRefreshedInOrder extends AbstractStorageDependencyTest {
    @BeforeEach
    void refreshInOrder() {
      HandoffTransactionProcessor.refreshSynchronously(true);
    }

    @Override
    protected String getVariantName() {
      return "Handoff (EVM v2, before Amsterdam, in-order refreshes)";
    }

    @Override
    protected BalConfiguration getBalConfiguration() {
      return CONFIG;
    }

    @Override
    protected boolean expectsBlockAccessList() {
      return false;
    }

    @Override
    protected ExecutionContextTestFixture createFreshContext() {
      return createOsakaContextOnEvmV2();
    }
  }

  @Nested
  @DisplayName("Refreshed results")
  class RefreshedResults extends AbstractParallelBlockProcessorIntegrationTest {
    private final Address counter = Address.fromHexStringStrict(PARALLEL_TEST_CONTRACT);

    @BeforeEach
    void refreshInOrder() {
      HandoffTransactionProcessor.refreshSynchronously(true);
    }

    @Override
    protected String getVariantName() {
      return "Handoff (EVM v2, before Amsterdam, in-order refreshes)";
    }

    @Override
    protected BalConfiguration getBalConfiguration() {
      return CONFIG;
    }

    @Override
    protected boolean expectsBlockAccessList() {
      return false;
    }

    @Override
    protected ExecutionContextTestFixture createFreshContext() {
      return createOsakaContextOnEvmV2();
    }

    private Transaction increment(final KeyPair sender) {
      return createContractCallTransaction(0, counter, "incrementSlot1", sender, Optional.empty());
    }

    @Test
    @DisplayName("Increments of one slot from different senders are all taken, refreshed in turn")
    void incrementsFromDifferentSendersAreTakenRefreshed() {
      final Transaction[] increments =
          SENDERS.stream().map(this::increment).toArray(Transaction[]::new);

      final ComparisonResult result = executeAndCompare(Wei.of(5), increments);

      assertContractStorageMatches(result.seqWorldState(), result.parWorldState(), counter, 0);
      assertContractStorage(result.parWorldState(), counter, 0, 10 + increments.length);
      // the first runs on the parent state, each later one on what the one before it leaves
      assertThat(result.parResult().getNbParallelizedTransactions()).hasValue(increments.length);
    }

    @Test
    @DisplayName("A result refreshed on a write the block does not make is executed instead")
    void refreshOnAWriteTheBlockDoesNotMakeIsNotTaken() {
      // the second sender sets the slot back before the third increments it, so the third's
      // refresh must follow the set, not the increments before it
      final Transaction first = increment(SENDERS.get(0));
      final Transaction set =
          createContractCallTransaction(0, counter, "setSlot1", SENDERS.get(1), Optional.of(500));
      final Transaction third = increment(SENDERS.get(2));

      final ComparisonResult result = executeAndCompare(Wei.of(5), first, set, third);

      assertContractStorageMatches(result.seqWorldState(), result.parWorldState(), counter, 0);
      assertContractStorage(result.parWorldState(), counter, 0, 501);
    }

    @Test
    @DisplayName(
        "A sender's next transaction whose sender was not known runs on the previous one's result")
    void nextTransactionOfAnUnknownSenderRunsOnThePreviousResult() {
      // decoded again, the transactions do not know their sender, as in a block from the network
      // whose transactions the pool did not have: they are not chained before the block runs
      final Transaction first = withoutKnownSender(increment(SENDERS.get(0)));
      final Transaction second =
          withoutKnownSender(
              createContractCallTransaction(
                  1, counter, "incrementSlot1", SENDERS.get(0), Optional.empty()));
      final Transaction other = withoutKnownSender(increment(SENDERS.get(1)));

      final ComparisonResult result = executeAndCompare(Wei.of(5), first, second, other);

      assertContractStorageMatches(result.seqWorldState(), result.parWorldState(), counter, 0);
      assertContractStorage(result.parWorldState(), counter, 0, 13);
      // the first on its own run, the second on top of the first's result, the third refreshed
      assertThat(result.parResult().getNbParallelizedTransactions()).hasValue(3);
    }

    @Test
    @DisplayName(
        "A chained transaction another sender's write invalidates is refreshed on its chain")
    void chainMemberIsRefreshedOnItsPredecessor() {
      // the second transaction of the first sender runs chained on its first one and reads the
      // counter it left, before the other sender's increment between them
      final Transaction firstOfA = increment(SENDERS.get(0));
      final Transaction ofB = increment(SENDERS.get(1));
      final Transaction secondOfA =
          createContractCallTransaction(
              1, counter, "incrementSlot1", SENDERS.get(0), Optional.empty());

      final ComparisonResult result = executeAndCompare(Wei.of(5), firstOfA, ofB, secondOfA);

      assertContractStorageMatches(result.seqWorldState(), result.parWorldState(), counter, 0);
      assertContractStorage(result.parWorldState(), counter, 0, 13);
      assertThat(result.parResult().getNbParallelizedTransactions()).hasValue(3);
    }

    @Test
    @DisplayName("A sender whose balance an earlier transaction only raised keeps its result")
    void senderWhoseBalanceWasRaisedKeepsItsResult() {
      final Address second = Util.publicKeyToAddress(SENDERS.get(1).getPublicKey());
      final Address recipient = Util.publicKeyToAddress(SENDERS.get(2).getPublicKey());
      final Transaction raise =
          createTransferTransaction(
              0, 1_000_000_000_000_000_000L, 21_000L, 0L, 5L, second.toHexString(), SENDERS.get(0));
      final Transaction spend =
          createTransferTransaction(
              0,
              2_000_000_000_000_000_000L,
              21_000L,
              0L,
              5L,
              recipient.toHexString(),
              SENDERS.get(1));

      final ComparisonResult result = executeAndCompare(Wei.of(5), raise, spend);

      assertAccountsMatch(result.seqWorldState(), result.parWorldState(), second);
      assertAccountsMatch(result.seqWorldState(), result.parWorldState(), recipient);
      assertThat(result.parResult().getNbParallelizedTransactions()).hasValue(2);
    }

    private Transaction withoutKnownSender(final Transaction transaction) {
      return Transaction.readFrom(transaction.encoded());
    }

    @Test
    @DisplayName("Interleaved slots refresh only the transactions that read a changed slot")
    void interleavedIncrementsMatchSequential() {
      final List<Transaction> txs = new ArrayList<>();
      for (int i = 0; i < SENDERS.size(); i++) {
        txs.add(
            i % 2 == 0
                ? increment(SENDERS.get(i))
                : createContractCallTransaction(
                    0, counter, "setSlot1", SENDERS.get(i), Optional.of(100 + i)));
      }

      final ComparisonResult result = executeAndCompare(Wei.of(5), txs.toArray(Transaction[]::new));

      assertContractStorageMatches(result.seqWorldState(), result.parWorldState(), counter, 0);
      assertThat(result.parWorldState().get(counter).getStorageValue(UInt256.ZERO))
          .isEqualTo(result.seqWorldState().get(counter).getStorageValue(UInt256.ZERO));
    }
  }
}
