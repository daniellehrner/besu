/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.consensus.merge.blockcreation;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.blockcreation.AbstractBlockCreator;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.SealableBlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter;
import org.hyperledger.besu.ethereum.mainnet.PoWSolution;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class MergeBlockCreator extends AbstractBlockCreator {

  MergeBlockCreator(
      final Address coinbase,
      final Supplier<Optional<Long>> targetGasLimitSupplier,
      final ExtraDataCalculator extraDataCalculator,
      final AbstractPendingTransactionsSorter pendingTransactions,
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule,
      final Wei minTransactionGasPrice,
      final Address miningBeneficiary,
      final Double minBlockOccupancyRatio,
      final BlockHeader parentHeader) {
    super(
        coinbase,
        targetGasLimitSupplier,
        extraDataCalculator,
        pendingTransactions,
        protocolContext,
        protocolSchedule,
        minTransactionGasPrice,
        miningBeneficiary,
        minBlockOccupancyRatio,
        parentHeader);
  }

  public Block createBlock(
      final Optional<List<Transaction>> maybeTransactions,
      final Bytes32 random,
      final long timestamp) {
    return createBlock(
        Optional.empty(),
        Optional.of(Collections.emptyList()),
        Optional.of(random),
        timestamp,
        false);
  }

  @Override
  public Block createBlock(
      final Optional<List<Transaction>> maybeTransactions,
      final Optional<List<BlockHeader>> maybeOmmers,
      final long timestamp) {
    throw new UnsupportedOperationException("random is required");
  }

  @Override
  protected BlockHeader createFinalBlockHeader(final SealableBlockHeader sealableBlockHeader) {
    final PoWSolution solution = new PoWSolution(0, Hash.ZERO, Bytes.EMPTY, Bytes.EMPTY);
    return BlockHeaderBuilder.create()
        .difficulty(Difficulty.ZERO)
        .populateFrom(sealableBlockHeader)
        .mixHash(solution.getMixHash())
        .nonce(solution.getNonce())
        .blockHeaderFunctions(blockHeaderFunctions)
        .buildBlockHeader();
  }
}