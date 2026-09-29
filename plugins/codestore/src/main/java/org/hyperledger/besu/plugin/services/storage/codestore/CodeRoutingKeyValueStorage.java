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
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.code.MappedCodeStorage;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorageTransaction;
import org.hyperledger.besu.plugin.services.storage.SnappableKeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SnappedKeyValueStorage;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;

/**
 * Serves {@code CODE_STORAGE} from the mmap code store and every other segment from a delegate.
 *
 * <p>Bonsai commits account, storage, trie and code writes in one transaction and expects it to be
 * atomic. Across two backends it cannot be, so {@link RoutingTransaction} commits the code first
 * and the delegate second. A crash in between leaves code that nothing refers to, which is inert;
 * the opposite order could leave an account naming code that does not exist, and is never used.
 *
 * <p>Bonsai casts its composed storage to {@link SnappableKeyValueStorage} without checking, hence
 * the interface. Code is immutable and content-addressed, so a snapshot reads it from the live
 * store: it can see code added after it was taken, but only under a hash that no account in the
 * snapshot holds.
 */
public class CodeRoutingKeyValueStorage implements SnappableKeyValueStorage, MappedCodeStorage {

  /** How the delegate's own {@code CODE_STORAGE} segment is used. */
  public enum DelegateCodeMode {
    /** The delegate never sees code. */
    IGNORE,
    /** Code is also written to the delegate, so switching back to it needs no migration. */
    MIRROR,
    /** As {@link #MIRROR}, and every read is checked against the delegate. */
    VERIFY
  }

  private final SegmentedKeyValueStorage delegate;
  private final KeyValueStorage code;
  private final DelegateCodeMode mode;

  /**
   * Creates the routing storage.
   *
   * @param delegate serves every segment except code
   * @param code serves the code segment
   * @param mode how the delegate's own code segment is used
   */
  public CodeRoutingKeyValueStorage(
      final SegmentedKeyValueStorage delegate,
      final KeyValueStorage code,
      final DelegateCodeMode mode) {
    this.delegate = delegate;
    this.code = code;
    this.mode = mode;
  }

  static boolean isCode(final SegmentIdentifier segment) {
    return KeyValueSegmentIdentifier.CODE_STORAGE.getName().equals(segment.getName());
  }

  // The code segment also carries Bonsai's strategy marker under a short key; that stays with the
  // delegate, the store only ever holds code under its hash.
  static boolean isCode(final SegmentIdentifier segment, final byte[] key) {
    return isCode(segment) && key.length == CodeStore.HASH_SIZE;
  }

  @Override
  public Optional<byte[]> get(final SegmentIdentifier segment, final byte[] key)
      throws StorageException {
    if (!isCode(segment, key)) {
      return delegate.get(segment, key);
    }
    final Optional<byte[]> value = code.get(key);
    if (mode == DelegateCodeMode.VERIFY) {
      final Optional<byte[]> expected = delegate.get(segment, key);
      if (value.isPresent() != expected.isPresent()
          || (value.isPresent() && !Arrays.equals(value.get(), expected.get()))) {
        throw mismatch(key, describe(value), describe(expected));
      }
    }
    return value;
  }

  @Override
  public <T> Optional<T> readCode(final byte[] codeHash, final Function<MemorySegment, T> reader) {
    if (mode != DelegateCodeMode.VERIFY) {
      return readStoredCode(codeHash, reader);
    }
    final Optional<byte[]> expected =
        delegate.get(KeyValueSegmentIdentifier.CODE_STORAGE, codeHash);
    final Optional<T> value =
        readStoredCode(
            codeHash,
            view -> {
              if (expected.isEmpty() || !matches(view, expected.get())) {
                throw mismatch(codeHash, view.byteSize() + " bytes", describe(expected));
              }
              return reader.apply(view);
            });
    if (value.isEmpty() && expected.isPresent()) {
      throw mismatch(codeHash, "absent", describe(expected));
    }
    return value;
  }

  private <T> Optional<T> readStoredCode(
      final byte[] codeHash, final Function<MemorySegment, T> reader) {
    return code instanceof MappedCodeStorage mapped
        ? mapped.readCode(codeHash, reader)
        : code.get(codeHash).map(value -> reader.apply(MemorySegment.ofArray(value)));
  }

  private static boolean matches(final MemorySegment view, final byte[] expected) {
    return view.byteSize() == expected.length
        && MemorySegment.mismatch(
                view, 0, view.byteSize(), MemorySegment.ofArray(expected), 0, expected.length)
            < 0;
  }

  private static StorageException mismatch(
      final byte[] key, final String value, final String expected) {
    return new StorageException(
        String.format(
            "Differential read mismatch for code %s: mmap store %s, delegate %s",
            Bytes.wrap(key).toHexString(), value, expected));
  }

  private static String describe(final Optional<byte[]> value) {
    return value.map(v -> v.length + " bytes").orElse("absent");
  }

  @Override
  public boolean containsKey(final SegmentIdentifier segment, final byte[] key)
      throws StorageException {
    return isCode(segment, key) ? code.containsKey(key) : delegate.containsKey(segment, key);
  }

  @Override
  public Optional<NearestKeyValue> getNearestBefore(
      final SegmentIdentifier segment, final Bytes key) throws StorageException {
    rejectOrderedCodeAccess(segment);
    return delegate.getNearestBefore(segment, key);
  }

  @Override
  public Optional<NearestKeyValue> getNearestAfter(final SegmentIdentifier segment, final Bytes key)
      throws StorageException {
    rejectOrderedCodeAccess(segment);
    return delegate.getNearestAfter(segment, key);
  }

  private static void rejectOrderedCodeAccess(final SegmentIdentifier segment) {
    if (isCode(segment)) {
      throw new UnsupportedOperationException("the mmap code store is not ordered by key");
    }
  }

  @Override
  public Stream<Pair<byte[], byte[]>> stream(final SegmentIdentifier segment) {
    return isCode(segment) ? code.stream() : delegate.stream(segment);
  }

  @Override
  public Stream<Pair<byte[], byte[]>> streamFromKey(
      final SegmentIdentifier segment, final byte[] startKey) {
    return isCode(segment)
        ? code.streamFromKey(startKey)
        : delegate.streamFromKey(segment, startKey);
  }

  @Override
  public Stream<Pair<byte[], byte[]>> streamFromKey(
      final SegmentIdentifier segment, final byte[] startKey, final byte[] endKey) {
    return isCode(segment)
        ? code.streamFromKey(startKey, endKey)
        : delegate.streamFromKey(segment, startKey, endKey);
  }

  @Override
  public Stream<byte[]> streamKeys(final SegmentIdentifier segment) {
    return isCode(segment) ? code.streamKeys() : delegate.streamKeys(segment);
  }

  @Override
  public boolean tryDelete(final SegmentIdentifier segment, final byte[] key)
      throws StorageException {
    return isCode(segment, key) ? code.tryDelete(key) : delegate.tryDelete(segment, key);
  }

  @Override
  public Set<byte[]> getAllKeysThat(
      final SegmentIdentifier segment, final Predicate<byte[]> returnCondition) {
    return isCode(segment)
        ? code.getAllKeysThat(returnCondition)
        : delegate.getAllKeysThat(segment, returnCondition);
  }

  @Override
  public Set<byte[]> getAllValuesFromKeysThat(
      final SegmentIdentifier segment, final Predicate<byte[]> returnCondition) {
    return isCode(segment)
        ? code.getAllValuesFromKeysThat(returnCondition)
        : delegate.getAllValuesFromKeysThat(segment, returnCondition);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Code records are immutable, so a rewrite of the code segment is only possible while the
   * store is empty, which is when Bonsai marks a new database with its code format.
   */
  @Override
  public void rewrite(
      final SegmentIdentifier segment,
      final BiFunction<byte[], byte[], byte[]> transform,
      final List<Pair<byte[], byte[]>> additions) {
    if (!isCode(segment)) {
      delegate.rewrite(segment, transform, additions);
      return;
    }
    final boolean empty;
    try (Stream<Pair<byte[], byte[]>> entries = code.stream()) {
      empty = entries.findAny().isEmpty();
    }
    if (!empty) {
      throw new StorageException(
          "The mmap code store holds code in a format this Besu version no longer writes, and"
              + " code records cannot be rewritten. Sync it again, or start the previous version.");
    }
    SnappableKeyValueStorage.super.rewrite(segment, transform, additions);
  }

  @Override
  public void clear(final SegmentIdentifier segment) {
    if (!isCode(segment)) {
      delegate.clear(segment);
      return;
    }
    code.clear();
    if (mode != DelegateCodeMode.IGNORE) {
      delegate.clear(segment);
    }
  }

  @Override
  public SegmentedKeyValueStorageTransaction startTransaction() throws StorageException {
    return new RoutingTransaction(delegate.startTransaction());
  }

  @Override
  public SegmentedKeyValueStorageTransaction startLowPriorityTransaction() throws StorageException {
    return new RoutingTransaction(delegate.startLowPriorityTransaction());
  }

  @Override
  public SnappedKeyValueStorage takeSnapshot() {
    if (!(delegate instanceof SnappableKeyValueStorage snappable)) {
      throw new UnsupportedOperationException(
          delegate.getClass().getSimpleName() + " does not support snapshots");
    }
    return new Snapshot(snappable.takeSnapshot(), code);
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  private final class RoutingTransaction implements SegmentedKeyValueStorageTransaction {
    private final SegmentedKeyValueStorageTransaction delegateTransaction;
    private final KeyValueStorageTransaction codeTransaction = code.startTransaction();

    private RoutingTransaction(final SegmentedKeyValueStorageTransaction delegateTransaction) {
      this.delegateTransaction = delegateTransaction;
    }

    @Override
    public void put(final SegmentIdentifier segment, final byte[] key, final byte[] value) {
      if (!isCode(segment, key)) {
        delegateTransaction.put(segment, key, value);
        return;
      }
      codeTransaction.put(key, value);
      if (mode != DelegateCodeMode.IGNORE) {
        delegateTransaction.put(segment, key, value);
      }
    }

    @Override
    public void remove(final SegmentIdentifier segment, final byte[] key) {
      if (isCode(segment, key)) {
        codeTransaction.remove(key);
      } else {
        delegateTransaction.remove(segment, key);
      }
    }

    @Override
    public void commit() throws StorageException {
      try {
        codeTransaction.commit();
      } catch (final RuntimeException e) {
        delegateTransaction.rollback();
        throw e;
      }
      delegateTransaction.commit();
    }

    @Override
    public void rollback() {
      codeTransaction.rollback();
      delegateTransaction.rollback();
    }

    @Override
    public void close() {
      codeTransaction.close();
      delegateTransaction.close();
    }
  }

  /**
   * A snapshot of the delegate with code read from the live store. Writes, code included, go to the
   * snapshot's own transaction and never reach the code store.
   */
  private static final class Snapshot extends CodeRoutingKeyValueStorage
      implements SnappedKeyValueStorage {
    private final SnappedKeyValueStorage snapshot;
    private final KeyValueStorage liveCode;

    private Snapshot(final SnappedKeyValueStorage snapshot, final KeyValueStorage liveCode) {
      super(snapshot, liveCode, DelegateCodeMode.IGNORE);
      this.snapshot = snapshot;
      this.liveCode = liveCode;
    }

    @Override
    public Optional<byte[]> get(final SegmentIdentifier segment, final byte[] key)
        throws StorageException {
      if (!isCode(segment, key)) {
        return snapshot.get(segment, key);
      }
      final Optional<byte[]> value = liveCode.get(key);
      return value.isPresent() ? value : snapshot.get(segment, key);
    }

    @Override
    public <T> Optional<T> readCode(
        final byte[] codeHash, final Function<MemorySegment, T> reader) {
      final Optional<T> value = super.readCode(codeHash, reader);
      return value.isPresent()
          ? value
          : snapshot
              .get(KeyValueSegmentIdentifier.CODE_STORAGE, codeHash)
              .map(stored -> reader.apply(MemorySegment.ofArray(stored)));
    }

    @Override
    public boolean containsKey(final SegmentIdentifier segment, final byte[] key)
        throws StorageException {
      return get(segment, key).isPresent();
    }

    @Override
    public void clear(final SegmentIdentifier segment) {
      snapshot.clear(segment);
    }

    @Override
    public SegmentedKeyValueStorageTransaction startTransaction() throws StorageException {
      return snapshot.startTransaction();
    }

    @Override
    public SegmentedKeyValueStorageTransaction startLowPriorityTransaction()
        throws StorageException {
      return snapshot.startLowPriorityTransaction();
    }

    @Override
    public SegmentedKeyValueStorageTransaction getSnapshotTransaction() {
      return snapshot.getSnapshotTransaction();
    }

    @Override
    public SnappedKeyValueStorage takeSnapshot() {
      throw new UnsupportedOperationException("cannot snapshot a snapshot");
    }
  }
}
