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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.AccountValue;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.chain.BlockAddedEvent;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.plugin.data.AddedBlockContext.EventType;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.plugin.services.trielogs.TrieLogEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountCacheTest {

  private AccountCache accountCache;
  private final Address testAddress = Address.fromHexString("0xdeadbeef");
  private final Hash testAddressHash = testAddress.addressHash();

  @BeforeEach
  void setUp() {
    // Create cache with 10MB limit for testing
    accountCache = new AccountCache(10L * 1024 * 1024);
  }

  @Test
  void shouldReturnEmptyOnCacheMiss() {
    Optional<Bytes> result = accountCache.getIfPresent(testAddressHash);
    assertThat(result).isEmpty();
  }

  @Test
  void shouldReturnCachedValueOnHit() {
    Bytes testData = Bytes.fromHexString("0x1234567890");
    accountCache.put(testAddressHash, testData);

    Optional<Bytes> result = accountCache.getIfPresent(testAddressHash);
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(testData);
  }

  @Test
  void shouldInvalidateSingleEntry() {
    Bytes testData = Bytes.fromHexString("0x1234567890");
    accountCache.put(testAddressHash, testData);

    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();

    accountCache.invalidate(testAddressHash);

    assertThat(accountCache.getIfPresent(testAddressHash)).isEmpty();
  }

  @Test
  void shouldInvalidateAllEntries() {
    Address address1 = Address.fromHexString("0xdeadbeef");
    Address address2 = Address.fromHexString("0xdeadbeee");

    accountCache.put(address1.addressHash(), Bytes.fromHexString("0x1111"));
    accountCache.put(address2.addressHash(), Bytes.fromHexString("0x2222"));

    assertThat(accountCache.getIfPresent(address1.addressHash())).isPresent();
    assertThat(accountCache.getIfPresent(address2.addressHash())).isPresent();

    accountCache.invalidateAll();

    assertThat(accountCache.getIfPresent(address1.addressHash())).isEmpty();
    assertThat(accountCache.getIfPresent(address2.addressHash())).isEmpty();
  }

  @Test
  void shouldNotUpdateCacheOnTrieLogAdded() {
    // TrieLog events no longer update the cache - cache updates are now synchronous
    // via the storage layer (putAccountInfoState/removeAccountInfoState)

    // Create mock AccountValue
    AccountValue accountValue = createMockAccountValue(1L, Wei.of(1000), Hash.EMPTY_TRIE_HASH);

    // Create mock TrieLog with account changes
    TrieLog trieLog = createMockTrieLog(testAddress, null, accountValue);
    TrieLogEvent event = createMockTrieLogEvent(trieLog);

    // Verify cache is empty before event
    assertThat(accountCache.getIfPresent(testAddressHash)).isEmpty();

    // Fire the event - this should NOT update the cache anymore
    accountCache.onTrieLogAdded(event);

    // Verify cache is still empty (onTrieLogAdded is now a no-op for cache updates)
    assertThat(accountCache.getIfPresent(testAddressHash)).isEmpty();
  }

  @Test
  void shouldNotRemoveFromCacheOnTrieLogDeleted() {
    // TrieLog events no longer update the cache - cache updates are now synchronous
    // via the storage layer (putAccountInfoState/removeAccountInfoState)

    // Pre-populate cache
    accountCache.put(testAddressHash, Bytes.fromHexString("0x1234"));
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();

    // Create mock TrieLog with account deletion (updated = null)
    TrieLog trieLog =
        createMockTrieLog(
            testAddress, createMockAccountValue(1L, Wei.ONE, Hash.EMPTY_TRIE_HASH), null);
    TrieLogEvent event = createMockTrieLogEvent(trieLog);

    // Fire the event - this should NOT modify the cache anymore
    accountCache.onTrieLogAdded(event);

    // Verify cache entry is still present (onTrieLogAdded is now a no-op for cache updates)
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();
  }

  @Test
  void shouldInvalidateCacheOnChainReorg() {
    // Pre-populate cache
    accountCache.put(testAddressHash, Bytes.fromHexString("0x1234"));
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();

    // Create a reorg event
    BlockDataGenerator gen = new BlockDataGenerator();
    Block block = gen.block();
    BlockAddedEvent reorgEvent =
        BlockAddedEvent.createForChainReorg(
            block,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Hash.ZERO);

    // Verify it's a CHAIN_REORG event
    assertThat(reorgEvent.getEventType()).isEqualTo(EventType.CHAIN_REORG);

    // Fire the event
    accountCache.onBlockAdded(reorgEvent);

    // Verify cache was invalidated
    assertThat(accountCache.getIfPresent(testAddressHash)).isEmpty();
  }

  @Test
  void shouldNotInvalidateCacheOnHeadAdvanced() {
    // Pre-populate cache
    accountCache.put(testAddressHash, Bytes.fromHexString("0x1234"));
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();

    // Create a HEAD_ADVANCED event
    BlockDataGenerator gen = new BlockDataGenerator();
    Block block = gen.block();
    BlockAddedEvent headAdvancedEvent =
        BlockAddedEvent.createForHeadAdvancement(
            block, Collections.emptyList(), Collections.emptyList());

    // Verify it's a HEAD_ADVANCED event
    assertThat(headAdvancedEvent.getEventType()).isEqualTo(EventType.HEAD_ADVANCED);

    // Fire the event
    accountCache.onBlockAdded(headAdvancedEvent);

    // Verify cache was NOT invalidated (cache updates come via TrieLog events)
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();
  }

  @Test
  void shouldInvalidateCacheOnClearStorage() {
    accountCache.put(testAddressHash, Bytes.fromHexString("0x1234"));
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();

    accountCache.onClearStorage();

    assertThat(accountCache.getIfPresent(testAddressHash)).isEmpty();
  }

  @Test
  void shouldInvalidateCacheOnClearFlatDatabaseStorage() {
    accountCache.put(testAddressHash, Bytes.fromHexString("0x1234"));
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();

    accountCache.onClearFlatDatabaseStorage();

    assertThat(accountCache.getIfPresent(testAddressHash)).isEmpty();
  }

  @Test
  void shouldInvalidateCacheOnClearTrieLog() {
    accountCache.put(testAddressHash, Bytes.fromHexString("0x1234"));
    assertThat(accountCache.getIfPresent(testAddressHash)).isPresent();

    accountCache.onClearTrieLog();

    assertThat(accountCache.getIfPresent(testAddressHash)).isEmpty();
  }

  // Helper methods for creating mocks

  private AccountValue createMockAccountValue(
      final long nonce, final Wei balance, final Hash storageRoot) {
    AccountValue accountValue = mock(AccountValue.class);
    when(accountValue.getNonce()).thenReturn(nonce);
    when(accountValue.getBalance()).thenReturn(balance);
    when(accountValue.getStorageRoot()).thenReturn(storageRoot);
    when(accountValue.getCodeHash()).thenReturn(Hash.EMPTY);

    // Mock writeTo to produce valid RLP
    org.mockito.Mockito.doAnswer(
            invocation -> {
              BytesValueRLPOutput out = (BytesValueRLPOutput) invocation.getArgument(0);
              out.startList();
              out.writeLongScalar(nonce);
              out.writeUInt256Scalar(balance);
              out.writeBytes(storageRoot);
              out.writeBytes(Hash.EMPTY);
              out.endList();
              return null;
            })
        .when(accountValue)
        .writeTo(org.mockito.ArgumentMatchers.any());

    return accountValue;
  }

  @SuppressWarnings("unchecked")
  private TrieLog createMockTrieLog(
      final Address address, final AccountValue prior, final AccountValue updated) {
    TrieLog trieLog = mock(TrieLog.class);
    TrieLog.LogTuple<AccountValue> logTuple = mock(TrieLog.LogTuple.class);

    when(logTuple.getPrior()).thenReturn(prior);
    when(logTuple.getUpdated()).thenReturn(updated);

    Map<Address, TrieLog.LogTuple<AccountValue>> accountChanges = new HashMap<>();
    accountChanges.put(address, logTuple);

    when(trieLog.getAccountChanges()).thenReturn(accountChanges);
    when(trieLog.getBlockHash()).thenReturn(Hash.ZERO);

    return trieLog;
  }

  private TrieLogEvent createMockTrieLogEvent(final TrieLog trieLog) {
    TrieLogEvent event = mock(TrieLogEvent.class);
    when(event.layer()).thenReturn(trieLog);
    when(event.getType()).thenReturn(TrieLogEvent.Type.ADDED);
    return event;
  }
}
