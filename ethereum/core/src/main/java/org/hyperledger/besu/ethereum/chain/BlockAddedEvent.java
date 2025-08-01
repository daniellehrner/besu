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
package org.hyperledger.besu.ethereum.chain;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.LogWithMetadata;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class BlockAddedEvent {

  private final Supplier<Block> blockSupplier;
  private final List<Transaction> addedTransactions;
  private final List<Transaction> removedTransactions;
  private final List<TransactionReceipt> transactionReceipts;
  private final EventType eventType;
  private final List<LogWithMetadata> logsWithMetadata;
  private final Hash commonAncestorHash;
  private final BlockHeader header;

  public enum EventType {
    HEAD_ADVANCED,
    FORK,
    CHAIN_REORG,
    STORED_ONLY
  }

  private BlockAddedEvent(
      final EventType eventType,
      final Supplier<Block> blockSupplier,
      final BlockHeader header,
      final List<Transaction> addedTransactions,
      final List<Transaction> removedTransactions,
      final List<TransactionReceipt> transactionReceipts,
      final List<LogWithMetadata> logsWithMetadata,
      final Hash commonAncestorHash) {
    this.eventType = eventType;
    this.blockSupplier = blockSupplier;
    this.header = header;
    this.addedTransactions = addedTransactions;
    this.removedTransactions = removedTransactions;
    this.transactionReceipts = transactionReceipts;
    this.logsWithMetadata = logsWithMetadata;
    this.commonAncestorHash = commonAncestorHash;
  }

  public static BlockAddedEvent createForSyncHeadAdvancement(
      final BlockHeader blockHeader,
      final Supplier<Block> blockSupplier,
      final List<LogWithMetadata> logsWithMetadata,
      final List<TransactionReceipt> transactionReceipts) {
    return new BlockAddedEvent(
        EventType.HEAD_ADVANCED,
        blockSupplier,
        blockHeader,
        Collections.emptyList(),
        Collections.emptyList(),
        transactionReceipts,
        logsWithMetadata,
        blockHeader.getParentHash());
  }

  public static BlockAddedEvent createForHeadAdvancement(
      final Block block,
      final List<LogWithMetadata> logsWithMetadata,
      final List<TransactionReceipt> transactionReceipts) {
    return new BlockAddedEvent(
        EventType.HEAD_ADVANCED,
        () -> block,
        block.getHeader(),
        block.getBody().getTransactions(),
        Collections.emptyList(),
        transactionReceipts,
        logsWithMetadata,
        block.getHeader().getParentHash());
  }

  public static BlockAddedEvent createForChainReorg(
      final Block block,
      final List<Transaction> addedTransactions,
      final List<Transaction> removedTransactions,
      final List<TransactionReceipt> transactionReceipts,
      final List<LogWithMetadata> logsWithMetadata,
      final Hash commonAncestorHash) {
    return new BlockAddedEvent(
        EventType.CHAIN_REORG,
        () -> block,
        block.getHeader(),
        addedTransactions,
        removedTransactions,
        transactionReceipts,
        logsWithMetadata,
        commonAncestorHash);
  }

  public static BlockAddedEvent createForFork(final Block block) {
    return new BlockAddedEvent(
        EventType.FORK,
        () -> block,
        block.getHeader(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        block.getHeader().getParentHash());
  }

  public static BlockAddedEvent createForStoredOnly(final Block block) {
    return new BlockAddedEvent(
        EventType.STORED_ONLY,
        () -> block,
        block.getHeader(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList(),
        block.getHeader().getParentHash());
  }

  public Block getBlock() {
    return blockSupplier.get();
  }

  public BlockHeader getHeader() {
    return header;
  }

  public boolean isNewCanonicalHead() {
    return eventType == EventType.HEAD_ADVANCED || eventType == EventType.CHAIN_REORG;
  }

  public EventType getEventType() {
    return eventType;
  }

  public List<Transaction> getAddedTransactions() {
    return addedTransactions;
  }

  public List<Transaction> getRemovedTransactions() {
    return removedTransactions;
  }

  public List<TransactionReceipt> getTransactionReceipts() {
    return transactionReceipts;
  }

  public List<LogWithMetadata> getLogsWithMetadata() {
    return logsWithMetadata;
  }

  public Hash getCommonAncestorHash() {
    return commonAncestorHash;
  }

  @Override
  public String toString() {
    return "BlockAddedEvent{"
        + "eventType="
        + eventType
        + ", block="
        + header.toLogString()
        + ", commonAncestorHash="
        + commonAncestorHash
        + ", addedTransactions count="
        + addedTransactions.size()
        + ", removedTransactions count="
        + removedTransactions.size()
        + ", transactionReceipts count ="
        + transactionReceipts.size()
        + ", logsWithMetadata count="
        + logsWithMetadata.size()
        + '}';
  }
}
