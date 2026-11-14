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
package org.hyperledger.besu.ethereum.mainnet.staterootcommitter;

import static org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldView.encodeTrieValue;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.mainnet.parallelization.BlockProcessingExecutors;
import org.hyperledger.besu.ethereum.trie.MerkleTrie;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.account.BonsaiAccount;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.jspecify.annotations.Nullable;

/**
 * Storage tries brought to the slot values a block is expected to leave while the block still
 * executes, so that computing the state root afterwards only corrects the slots that ended
 * otherwise.
 *
 * <p>Each account's trie starts from the parent state's storage root, read from the world state
 * storage, which the block does not change before it persists. The tries are hashed in the
 * background; {@link DefaultStateRootCommitter} takes one over only if it is done and starts from
 * the root the block's own computation would start from.
 */
public final class PrecomputedStorageTries {

  /**
   * A storage trie with the predicted slot values applied, and hashed.
   *
   * @param startRoot the storage root it started from
   * @param trie the trie
   * @param predicted the slot values applied, a value of zero removing the slot
   */
  public record Precomputed(
      Hash startRoot, MerkleTrie<Bytes, Bytes> trie, Map<StorageSlotKey, UInt256> predicted) {}

  private final BonsaiWorldState worldState;
  private final Map<Address, CompletableFuture<Precomputed>> tries = new ConcurrentHashMap<>();

  // bench counters
  private final AtomicInteger started = new AtomicInteger();
  private final AtomicInteger taken = new AtomicInteger();
  private final AtomicInteger notDone = new AtomicInteger();

  /**
   * Creates the tries of a block processed on the world state.
   *
   * @param worldState the world state the block is processed on
   */
  public PrecomputedStorageTries(final BonsaiWorldState worldState) {
    this.worldState = worldState;
  }

  /**
   * Starts bringing the storage trie of each account to the given slot values, on the storage trie
   * pool.
   *
   * @param predicted the slot values by account
   */
  public void start(final Map<Address, Map<StorageSlotKey, UInt256>> predicted) {
    for (final Map.Entry<Address, Map<StorageSlotKey, UInt256>> account : predicted.entrySet()) {
      if (account.getValue().isEmpty()) {
        continue;
      }
      started.incrementAndGet();
      tries.put(
          account.getKey(),
          CompletableFuture.supplyAsync(
              () -> precompute(account.getKey(), account.getValue()),
              BlockProcessingExecutors.storageTrieExecutor()));
    }
  }

  private Precomputed precompute(final Address address, final Map<StorageSlotKey, UInt256> slots) {
    final Hash addressHash = address.addressHash();
    final Hash startRoot =
        worldState
            .getWorldStateStorage()
            .getAccount(addressHash)
            .map(
                rlp ->
                    BonsaiAccount.fromRLP(worldState, address, rlp, false, worldState.codeCache())
                        .getStorageRoot())
            .orElse(Hash.EMPTY_TRIE_HASH);
    final MerkleTrie<Bytes, Bytes> trie = worldState.createStorageTrie(addressHash, startRoot);
    for (final Map.Entry<StorageSlotKey, UInt256> slot : slots.entrySet()) {
      apply(trie, slot.getKey(), slot.getValue());
    }
    trie.getRootHash();
    return new Precomputed(startRoot, trie, slots);
  }

  /**
   * Puts the slot value into the trie, or removes the slot for zero or null.
   *
   * @param trie the storage trie
   * @param slot the slot
   * @param value the value
   */
  static void apply(
      final MerkleTrie<Bytes, Bytes> trie,
      final StorageSlotKey slot,
      final @Nullable UInt256 value) {
    if (value == null || value.isZero()) {
      trie.remove(slot.getSlotHash().getBytes());
    } else {
      trie.put(slot.getSlotHash().getBytes(), encodeTrieValue(value));
    }
  }

  /**
   * Takes the account's precomputed trie if it is done; it is not handed out again.
   *
   * @param address the account
   * @return the precomputed trie, or null
   */
  public @Nullable Precomputed take(final Address address) {
    final CompletableFuture<Precomputed> future = tries.remove(address);
    if (future == null) {
      return null;
    }
    if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
      notDone.incrementAndGet();
      future.cancel(false);
      return null;
    }
    taken.incrementAndGet();
    return future.join();
  }

  @VisibleForTesting
  int doneForTest() {
    return (int) tries.values().stream().filter(CompletableFuture::isDone).count();
  }

  /** Drops the tries not taken. */
  public void cancel() {
    tries.values().forEach(future -> future.cancel(false));
    tries.clear();
  }

  /**
   * The number of tries started, taken and not done in time, for the bench log.
   *
   * @return started, taken and not done
   */
  public int[] counters() {
    return new int[] {started.get(), taken.get(), notDone.get()};
  }
}
