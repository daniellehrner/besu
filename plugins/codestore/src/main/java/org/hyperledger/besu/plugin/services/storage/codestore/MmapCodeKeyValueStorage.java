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

import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageTransaction;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/**
 * The {@code CODE_STORAGE} segment served from a {@link CodeStore}.
 *
 * <p>Only code keyed by its keccak hash is accepted; that is what makes the store
 * content-addressed, and it is checked on every commit because Besu does not tell a storage plugin
 * which keying it uses. Entries are never removed individually. Streams are in insertion order, not
 * key order.
 *
 * <p>Closing this view does not close the store, which belongs to the factory.
 */
public class MmapCodeKeyValueStorage implements KeyValueStorage {

  private final CodeStore store;
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Creates the view.
   *
   * @param store the store to serve code from
   */
  public MmapCodeKeyValueStorage(final CodeStore store) {
    this.store = store;
  }

  /**
   * Zero-copy lookup. Internal on purpose: nothing outside this class may hold a segment, so that a
   * later stage can promote this method without restructuring.
   */
  private Optional<MemorySegment> view(final byte[] codeHash) {
    return store.get(codeHash);
  }

  @Override
  public Optional<byte[]> get(final byte[] key) throws StorageException {
    return isCodeHash(key)
        ? view(key).map(segment -> segment.toArray(ValueLayout.JAVA_BYTE))
        : Optional.empty();
  }

  @Override
  public boolean containsKey(final byte[] key) throws StorageException {
    return isCodeHash(key) && store.contains(key);
  }

  // Anything that is not a non-zero 32-byte key cannot be stored, so it is simply absent.
  private static boolean isCodeHash(final byte[] key) {
    if (key.length != CodeStore.HASH_SIZE) {
      return false;
    }
    for (final byte b : key) {
      if (b != 0) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void clear() throws StorageException {
    try {
      store.clear();
    } catch (final IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public Stream<Pair<byte[], byte[]>> stream() throws StorageException {
    return store.stream().map(entry -> Pair.of(entry.getKey(), entry.getValue()));
  }

  @Override
  public Stream<Pair<byte[], byte[]>> streamFromKey(final byte[] startKey) {
    return stream().filter(pair -> Arrays.compareUnsigned(pair.getKey(), startKey) >= 0);
  }

  @Override
  public Stream<Pair<byte[], byte[]>> streamFromKey(final byte[] startKey, final byte[] endKey) {
    return streamFromKey(startKey)
        .filter(pair -> Arrays.compareUnsigned(pair.getKey(), endKey) <= 0);
  }

  @Override
  public Stream<byte[]> streamKeys() throws StorageException {
    return stream().map(Pair::getKey);
  }

  @Override
  public boolean tryDelete(final byte[] key) throws StorageException {
    throw removalUnsupported();
  }

  @Override
  public Set<byte[]> getAllKeysThat(final Predicate<byte[]> returnCondition) {
    return streamKeys().filter(returnCondition).collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Set<byte[]> getAllValuesFromKeysThat(final Predicate<byte[]> returnCondition) {
    return stream()
        .filter(pair -> returnCondition.test(pair.getKey()))
        .map(Pair::getValue)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public KeyValueStorageTransaction startTransaction() throws StorageException {
    return new Transaction();
  }

  @Override
  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    closed.set(true);
  }

  /**
   * The code inside a stored value. Bonsai stores either the bare code or, with its jump
   * destination analysis, {@code length u32 | code | bitmask} with one bit per code byte.
   */
  static Bytes codeIn(final byte[] value) {
    if (value.length >= Integer.BYTES) {
      final int length = ByteBuffer.wrap(value).getInt();
      if (length >= 0
          && value.length == Integer.BYTES + length + ((length >> 6) + 1) * Long.BYTES) {
        return Bytes.wrap(value, Integer.BYTES, length);
      }
    }
    return Bytes.wrap(value);
  }

  static StorageException removalUnsupported() {
    return new StorageException(
        "The mmap code store is content-addressed and never removes code. A removal means Bonsai"
            + " is keying code by account hash, which this store does not support.");
  }

  /**
   * Buffers puts in memory. On commit the records are appended to the log, the log is forced, the
   * index is updated, and then the index header's {@code logLength} is advanced and forced.
   *
   * <p>That order makes the commit durable, not atomic. A process that dies part-way leaves a
   * prefix of the batch in the log, and recovery keeps every complete record it finds. This is
   * sound only because a record is addressed by the hash of its content: a record nobody refers to
   * is inert, identical to what a SELFDESTRUCT leaves behind, since Bonsai never deletes code keyed
   * by hash. The rule that follows is an ordering one and is the caller's to keep: commit the code
   * before committing any state that names it.
   */
  private final class Transaction implements KeyValueStorageTransaction {
    private final Map<Bytes, byte[]> puts = new LinkedHashMap<>();

    @Override
    public void put(final byte[] key, final byte[] value) {
      puts.put(Bytes.wrap(key), value);
    }

    @Override
    public void remove(final byte[] key) {
      throw removalUnsupported();
    }

    @Override
    public void commit() throws StorageException {
      if (puts.isEmpty()) {
        return;
      }
      puts.forEach(
          (key, value) -> {
            if (!Hash.keccak256(codeIn(value)).equals(key)) {
              throw new StorageException(
                  "Refusing to store code under key "
                      + key.toHexString()
                      + ", which is not its keccak hash. The mmap code store requires Bonsai to"
                      + " key code by code hash (--Xbonsai-code-using-code-hash-enabled=true,"
                      + " the default).");
            }
          });
      store.putAllAndSync(
          puts.entrySet().stream()
              .map(e -> Map.entry(e.getKey().toArrayUnsafe(), e.getValue()))
              .toList());
      puts.clear();
    }

    @Override
    public void rollback() {
      puts.clear();
    }

    @Override
    public void close() {
      puts.clear();
    }
  }
}
