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

import static org.hyperledger.besu.ethereum.storage.keyvalue.KeyValueSegmentIdentifier.CODE_STORAGE;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.code.JumpDestCodeStorageStrategy;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.plugin.services.storage.SegmentedKeyValueStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * A whole code load: from a code hash to the {@link Code} the EVM runs, with its jump destination
 * analysis. The interesting part is how often the code is copied on the way — {@code bytes} copies
 * the stored value out of the store and then the code out of that value, {@code in-place} decodes
 * the value where it lies.
 *
 * <p>Each code size gets its own store of 128 MiB of code, built under {@code
 * -Dcodestore.bench.work} on first use and kept for later runs. Run with {@code -prof gc} to see
 * the allocation per load, which is what the copies cost beyond the copying itself.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(2)
public class CodeLoadBenchmark {

  /** System property naming the directory the stores are built in. */
  public static final String WORK_DIR_PROPERTY = "codestore.bench.work";

  private static final long TOTAL_BYTES = 128L << 20;

  /** Bytes of code per contract. 24576 is the deployment limit, 65536 what a factory can reach. */
  @Param({"512", "4096", "24576", "65536"})
  public int size;

  /** Which read path builds the code. */
  @Param({"in-place", "bytes", "rocksdb"})
  public String path;

  private CodeStore store;
  private MmapCodeKeyValueStorage mmap;
  private BenchmarkRocksDb rocksDb;
  private SegmentedKeyValueStorage rocks;
  private byte[][] keys;
  private Hash[] hashes;
  private int entries;
  private int cursor;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    final Path work = Path.of(System.getProperty(WORK_DIR_PROPERTY, "")).toAbsolutePath();
    final Path dir = work.resolve("code-" + size);
    final Path rocksDir = work.resolve("code-" + size + "-rocksdb");
    final boolean fresh = Files.notExists(dir.resolve(CodeLog.FILE_NAME));
    store = CodeStore.open(dir);
    if (fresh) {
      fill();
    }
    mmap = new MmapCodeKeyValueStorage(store);
    keys = new byte[Math.toIntExact(store.entries())][];
    hashes = new Hash[keys.length];
    store.forEach((hash, code) -> add(hash));
    rocksDb = new BenchmarkRocksDb(rocksDir);
    if (!rocksDb.isBuilt()) {
      rocksDb.fillFrom(store);
    }
    rocks = rocksDb.storage();
    pageCache(dir, rocksDir);
  }

  private void fill() {
    final SplittableRandom random = new SplittableRandom(size);
    final byte[] code = new byte[size];
    for (long written = 0; written < TOTAL_BYTES; written += size) {
      random.nextBytes(code);
      final Bytes bytes = Bytes.wrap(code);
      store.put(
          Hash.hash(bytes).getBytes().toArrayUnsafe(), JumpDestCodeStorageStrategy.encode(bytes));
    }
    store.sync();
  }

  private void add(final byte[] hash) {
    keys[entries] = hash;
    hashes[entries] = Hash.wrap(Bytes32.wrap(hash));
    entries++;
  }

  /** The store and the column family are both read from the page cache, not from the disk. */
  private void pageCache(final Path... dirs) throws Exception {
    final StringBuilder command = new StringBuilder("cat");
    for (final Path dir : dirs) {
      command.append(' ').append(dir).append("/*");
    }
    final Process process =
        new ProcessBuilder("sh", "-c", command.append(" > /dev/null").toString())
            .inheritIO()
            .start();
    if (process.waitFor() != 0) {
      throw new IllegalStateException("page cache preparation failed");
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    rocksDb.close();
    store.close();
  }

  @Benchmark
  public Code load() {
    cursor = cursor + 1 == entries ? 0 : cursor + 1;
    final byte[] key = keys[cursor];
    final Hash hash = hashes[cursor];
    return switch (path) {
      case "in-place" ->
          mmap.readCode(key, value -> JumpDestCodeStorageStrategy.decode(value, hash))
              .orElseThrow();
      case "bytes" -> JumpDestCodeStorageStrategy.decode(mmap.get(key).orElseThrow(), hash);
      case "rocksdb" ->
          JumpDestCodeStorageStrategy.decode(rocks.get(CODE_STORAGE, key).orElseThrow(), hash);
      default -> throw new IllegalArgumentException(path);
    };
  }
}
