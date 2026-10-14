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
package org.hyperledger.besu.ethereum.eth.sync.snap2.storage;

import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_INFO_STATE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.ACCOUNT_STORAGE_STORAGE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;
import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.TRIE_BRANCH_STORAGE;
import static org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage.WORLD_BLOCK_NUMBER_KEY;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.trienode.BonsaiTrieNodeStrategy;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.trienode.TrieNodeKey;
import org.hyperledger.besu.ethereum.worldstate.FlatDbMode;
import org.hyperledger.besu.plugin.services.storage.SegmentBulkLoad;
import org.hyperledger.besu.plugin.services.storage.SortedWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sync storage writing straight into the Bonsai flat database and trie node store. */
public final class BonsaiSyncStateStorage extends AbstractSyncStateStorage {

  // the storage nodes of one account sorted in memory before going to a file
  private static final long MAX_SORTED_STORAGE_BYTES = 32L * 1024 * 1024;

  private static final Logger LOG = LoggerFactory.getLogger(BonsaiSyncStateStorage.class);

  private final BonsaiWorldStateKeyValueStorage bonsai;
  private final long maxSortedStorageBytes;
  private final boolean sortedFlatState;
  private SortedIngestStats stats = new SortedIngestStats();
  private SortedStateIngest ingest;
  private SegmentBulkLoad bulkLoad;

  public BonsaiSyncStateStorage(final BonsaiWorldStateKeyValueStorage bonsai) {
    this(bonsai, false);
  }

  /**
   * Creates the storage.
   *
   * @param bonsai the Bonsai storage
   * @param sortedFlatState whether to write the downloaded accounts and slots as sorted files too,
   *     with the compaction of the state segments held back until the sync is complete
   */
  public BonsaiSyncStateStorage(
      final BonsaiWorldStateKeyValueStorage bonsai, final boolean sortedFlatState) {
    this(bonsai, MAX_SORTED_STORAGE_BYTES, sortedFlatState);
  }

  @VisibleForTesting
  BonsaiSyncStateStorage(
      final BonsaiWorldStateKeyValueStorage bonsai, final long maxSortedStorageBytes) {
    this(bonsai, maxSortedStorageBytes, false);
  }

  private BonsaiSyncStateStorage(
      final BonsaiWorldStateKeyValueStorage bonsai,
      final long maxSortedStorageBytes,
      final boolean sortedFlatState) {
    super(bonsai.getComposedWorldStateStorage(), ACCOUNT_INFO_STATE, ACCOUNT_STORAGE_STORAGE);
    this.bonsai = bonsai;
    this.maxSortedStorageBytes = maxSortedStorageBytes;
    this.sortedFlatState = sortedFlatState;
  }

  @Override
  public Optional<SortedWrites> sortedWrites() {
    return Optional.ofNullable(ingest);
  }

  @Override
  public void deleteStorage(final Bytes32 accountHash) {
    if (ingest != null) {
      ingest.storageRemoved(accountHash);
    }
    super.deleteStorage(accountHash);
  }

  @Override
  public boolean hasCode(final Bytes32 codeHash) {
    return flat.containsKey(CODE_STORAGE, codeHash.toArrayUnsafe());
  }

  @Override
  protected Bytes storedSlot(final Bytes32 value) {
    return value;
  }

  @Override
  public Batch batch() {
    final BonsaiWorldStateKeyValueStorage.Updater updater = newUpdater();
    return new FlatBatch(updater.getWorldStateTransaction()) {
      // stored by code hash, which prepareForSync requires
      @Override
      public void putCode(final Bytes32 codeHash, final Bytes code) {
        updater.putCode(Hash.ZERO, Hash.wrap(codeHash), code);
      }

      @Override
      public void commit() {
        updater.commitComposedOnly();
      }
    };
  }

  @Override
  public boolean hasStorageTrie(final Bytes32 accountHash, final Bytes32 storageRoot) {
    return bonsai
        .getTrieNode(TrieNodeKey.of(Hash.wrap(accountHash), Bytes.EMPTY), storageRoot)
        .isPresent();
  }

  @Override
  public TrieWriter trieWriter() {
    return new TrieWriter() {
      private BonsaiWorldStateKeyValueStorage.Updater updater;
      private long pendingBytes;

      @Override
      public void accountNode(final Bytes location, final Bytes32 hash, final Bytes rlp) {
        put(location, hash, rlp);
      }

      @Override
      public void storageNode(
          final Bytes32 accountHash, final Bytes location, final Bytes32 hash, final Bytes rlp) {
        put(TrieNodeKey.of(Hash.wrap(accountHash), location), hash, rlp);
      }

      @Override
      public void commit() {
        if (updater != null) {
          updater.commitComposedOnly();
          updater = null;
          pendingBytes = 0;
        }
      }

      private void put(final Bytes key, final Bytes32 hash, final Bytes rlp) {
        if (updater == null) {
          updater = newUpdater();
        }
        updater.putTrieNode(key, hash, rlp);
        pendingBytes += key.size() + rlp.size();
        if (pendingBytes >= BYTES_PER_COMMIT) {
          commit();
        }
      }
    };
  }

  @Override
  public TrieWriter partitionTrieWriter() {
    // the files hold the nodes under the keys of the default strategy only
    return bonsai.getTrieNodeStrategy().getClass() == BonsaiTrieNodeStrategy.class
        ? new SortedStorageTrieWriter(trieWriter())
        : trieWriter();
  }

  /**
   * Sorts the storage nodes of each account and adds them to a file of the partition, which skips
   * the compactions of the trie segment. The account nodes, and a storage too large to sort in
   * memory, take the usual writes, the file being closed before such a storage so that it holds
   * none of its keys.
   */
  private final class SortedStorageTrieWriter implements TrieWriter {
    private final TrieWriter usual;
    private final List<StorageNode> nodes = new ArrayList<>();
    private Bytes32 accountHash;
    private long sortedBytes;
    private boolean tooLarge;
    private SortedWriter file;

    private record StorageNode(byte[] key, Bytes location, Bytes32 hash, Bytes rlp) {}

    private SortedStorageTrieWriter(final TrieWriter usual) {
      this.usual = usual;
    }

    @Override
    public void accountNode(final Bytes location, final Bytes32 hash, final Bytes rlp) {
      usual.accountNode(location, hash, rlp);
    }

    @Override
    public void storageNode(
        final Bytes32 accountHash, final Bytes location, final Bytes32 hash, final Bytes rlp) {
      if (hash.equals(MerkleTrie.EMPTY_TRIE_NODE_HASH)) {
        return;
      }
      if (!accountHash.equals(this.accountHash)) {
        finishAccount();
        this.accountHash = accountHash.copy();
      }
      if (tooLarge) {
        usual.storageNode(accountHash, location, hash, rlp);
        return;
      }
      final byte[] key = TrieNodeKey.of(Hash.wrap(accountHash), location).toArrayUnsafe();
      nodes.add(new StorageNode(key, location, hash, rlp));
      sortedBytes += key.length + rlp.size();
      if (sortedBytes > maxSortedStorageBytes) {
        closeFile();
        nodes.forEach(
            node -> usual.storageNode(accountHash, node.location(), node.hash(), node.rlp()));
        nodes.clear();
        tooLarge = true;
      }
    }

    @Override
    public void commit() {
      finishAccount();
      closeFile();
      usual.commit();
    }

    @Override
    public void abort() {
      if (file != null) {
        file.rollback();
        file = null;
      }
    }

    private void finishAccount() {
      if (!nodes.isEmpty()) {
        nodes.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        if (file == null) {
          file = flat.startSortedWriter(TRIE_BRANCH_STORAGE);
        }
        nodes.forEach(node -> file.put(node.key(), node.rlp().toArrayUnsafe()));
      }
      nodes.clear();
      sortedBytes = 0;
      tooLarge = false;
    }

    private void closeFile() {
      if (file != null) {
        file.commit();
        file = null;
      }
    }
  }

  @Override
  public void saveWorldState(final BlockHeader header, final Bytes rootNode) {
    final BonsaiWorldStateKeyValueStorage.Updater updater = newUpdater();
    updater.saveWorldState(
        header.getBlockHash().getBytes(), Bytes32.wrap(header.getStateRoot().getBytes()), rootNode);
    updater
        .getWorldStateTransaction()
        .put(
            TRIE_BRANCH_STORAGE,
            WORLD_BLOCK_NUMBER_KEY,
            Bytes.ofUnsignedLong(header.getNumber()).toArrayUnsafe());
    updater.commitComposedOnly();
  }

  @Override
  public void prepareForSync() {
    endSortedFlatState();
    bonsai.clear();
    if (sortedFlatState) {
      stats = new SortedIngestStats();
      ingest = new SortedStateIngest(flat, ACCOUNT_INFO_STATE, ACCOUNT_STORAGE_STORAGE, stats);
      // the compactions of the state segments wait, so that the files stay where they are put
      bulkLoad =
          flat.startBulkLoad(
              List.of(ACCOUNT_INFO_STATE, ACCOUNT_STORAGE_STORAGE, TRIE_BRANCH_STORAGE));
    }
    if (!bonsai.getFlatDbStrategy().isCodeByCodeHash()) {
      throw new IllegalStateException(
          "SNAP2 sync needs Bonsai to store bytecode by code hash (--Xbonsai-code-using-code-hash-enabled)");
    }
  }

  @Override
  public void flush() {
    flat.flush();
  }

  @Override
  public void completeSync() {
    if (ingest != null) {
      LOG.info("SNAP2 accounts and slots written with {}", stats);
    }
    endSortedFlatState();
    if (bonsai.getFlatDbMode() != FlatDbMode.FULL) {
      bonsai.upgradeToFullFlatDbMode();
    }
    bonsai.clearCrossBlockCache();
  }

  // the sync is the only writer, its writes skip the conflict checks and the write-ahead log
  private BonsaiWorldStateKeyValueStorage.Updater newUpdater() {
    return new BonsaiWorldStateKeyValueStorage.Updater(
        flat.startUnloggedBatchTransaction(),
        bonsai.getTrieLogStorage().startUnloggedBatchTransaction(),
        bonsai.getFlatDbStrategy(),
        flat,
        bonsai.getTrieNodeStrategy());
  }

  private void endSortedFlatState() {
    if (ingest != null) {
      ingest.close();
      ingest = null;
    }
    if (bulkLoad != null) {
      bulkLoad.close();
      bulkLoad = null;
    }
  }
}
