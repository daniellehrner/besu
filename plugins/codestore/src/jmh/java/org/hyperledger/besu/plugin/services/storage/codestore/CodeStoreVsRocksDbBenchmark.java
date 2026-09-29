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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

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
 * Code reads through the {@code KeyValueStorage} boundary, mmap store against RocksDB, on a real
 * code store copied from a node. Point {@code -Dcodestore.bench.dir} at the copy; the RocksDB
 * column family is built from it next to it on first use, with the options Besu gives {@code
 * CODE_STORAGE}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(2)
public class CodeStoreVsRocksDbBenchmark {

  private static final int HOT_SET = 1_000;

  /** Which backend serves the read. */
  @Param({"mmap", "rocksdb"})
  public String backend;

  /**
   * {@code all}: uniform over every entry; {@code hot}: uniform over a small subset that fits any
   * cache; {@code miss}: hashes that are not stored.
   */
  @Param({"all", "hot", "miss"})
  public String keys;

  /** {@code warm}: files stay in the page cache; {@code cold}: evicted before every iteration. */
  @Param({"warm", "cold"})
  public String cache;

  private Path storeDir;
  private Path rocksDir;
  private CodeStore store;
  private MmapCodeKeyValueStorage mmap;
  private BenchmarkRocksDb rocksDb;
  private byte[][] hashes;
  private int cursor;
  private boolean warmed;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    storeDir = Path.of(System.getProperty("codestore.bench.dir", "")).toAbsolutePath();
    if (!Files.isRegularFile(storeDir.resolve(CodeLog.FILE_NAME))) {
      throw new IllegalStateException("-Dcodestore.bench.dir must point at a code store");
    }
    rocksDir = storeDir.resolveSibling(storeDir.getFileName() + "-rocksdb");
    store = CodeStore.open(storeDir);
    mmap = new MmapCodeKeyValueStorage(store);

    rocksDb = new BenchmarkRocksDb(rocksDir);
    if (!rocksDb.isBuilt()) {
      rocksDb.fillFrom(store);
    }
    hashes = select(allHashes());
  }

  private byte[][] allHashes() {
    final List<byte[]> list = new ArrayList<>();
    store.forEach((hash, code) -> list.add(hash));
    return list.toArray(new byte[0][]);
  }

  private byte[][] select(final byte[][] all) {
    final SplittableRandom rng = new SplittableRandom(7);
    for (int i = all.length - 1; i > 0; i--) {
      final int j = rng.nextInt(i + 1);
      final byte[] tmp = all[i];
      all[i] = all[j];
      all[j] = tmp;
    }
    switch (keys) {
      case "all":
        return all;
      case "hot":
        return Arrays.copyOf(all, HOT_SET);
      case "miss":
        final byte[][] absent = new byte[1 << 16][];
        for (int i = 0; i < absent.length; i++) {
          absent[i] = new byte[CodeStore.HASH_SIZE];
          rng.nextBytes(absent[i]);
          absent[i][31] |= 1;
        }
        return absent;
      default:
        throw new IllegalArgumentException(keys);
    }
  }

  /** Warm: everything read into the page cache once. Cold: reopened over an evicted page cache. */
  @Setup(Level.Iteration)
  public void prepareCache() throws Exception {
    if ("warm".equals(cache)) {
      if (!warmed) {
        pageCache("cat %s/* %s/* > /dev/null");
        warmed = true;
      }
      return;
    }
    // A mapping pins its pages, and RocksDB keeps a block cache: both have to go first.
    rocksDb.close();
    store.close();
    pageCache(
        "python3 -c 'import os,sys\n"
            + "for d in sys.argv[1:]:\n"
            + "  for f in os.listdir(d):\n"
            + "    fd=os.open(os.path.join(d,f),os.O_RDONLY)\n"
            + "    os.posix_fadvise(fd,0,0,os.POSIX_FADV_DONTNEED); os.close(fd)' %s %s");
    store = CodeStore.open(storeDir);
    mmap = new MmapCodeKeyValueStorage(store);
    rocksDb = new BenchmarkRocksDb(rocksDir);
  }

  private void pageCache(final String commandTemplate) throws Exception {
    final Process p =
        new ProcessBuilder("sh", "-c", String.format(commandTemplate, storeDir, rocksDir))
            .inheritIO()
            .start();
    if (p.waitFor() != 0) {
      throw new IllegalStateException("page cache preparation failed");
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    rocksDb.close();
    store.close();
  }

  private byte[] next() {
    cursor = cursor + 1 == hashes.length ? 0 : cursor + 1;
    return hashes[cursor];
  }

  @Benchmark
  public Optional<byte[]> get() {
    final byte[] key = next();
    return "mmap".equals(backend) ? mmap.get(key) : rocksDb.storage().get(CODE_STORAGE, key);
  }
}
