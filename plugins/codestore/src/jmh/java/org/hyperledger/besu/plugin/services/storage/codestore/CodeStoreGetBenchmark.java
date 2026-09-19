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

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
 * {@code get} latency over a working set that is resident in the page cache. {@code view} is the
 * zero-copy lookup, {@code copy} adds the {@code byte[]} copy the Besu storage SPI forces, and
 * {@code miss} looks up hashes that are not stored.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class CodeStoreGetBenchmark {

  @Param({"100000"})
  public int entries;

  private Path dir;
  private CodeStore store;
  private byte[][] present;
  private byte[][] absent;
  private int cursor;

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("codestore-jmh");
    store = CodeStore.open(dir);
    final SplittableRandom rng = new SplittableRandom(42);
    present = new byte[entries][];
    for (int i = 0; i < entries; i++) {
      present[i] = randomHash(rng);
      // Roughly the mainnet shape: most contracts are a few KiB, a tail reaches the 24 KiB cap.
      final byte[] code = new byte[rng.nextInt(10) == 0 ? rng.nextInt(24_577) : rng.nextInt(6_000)];
      rng.nextBytes(code);
      store.put(present[i], code);
    }
    store.sync();
    absent = new byte[4096][];
    for (int i = 0; i < absent.length; i++) {
      absent[i] = randomHash(rng);
    }
    // Visit the keys in an order unrelated to their position in the log.
    for (int i = entries - 1; i > 0; i--) {
      final int j = rng.nextInt(i + 1);
      final byte[] tmp = present[i];
      present[i] = present[j];
      present[j] = tmp;
    }
  }

  private static byte[] randomHash(final SplittableRandom rng) {
    final byte[] hash = new byte[CodeStore.HASH_SIZE];
    rng.nextBytes(hash);
    hash[31] |= 1;
    return hash;
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    store.close();
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private byte[] nextPresent() {
    cursor = cursor + 1 == entries ? 0 : cursor + 1;
    return present[cursor];
  }

  @Benchmark
  public MemorySegment view() {
    return store.get(nextPresent()).orElseThrow();
  }

  @Benchmark
  public byte[] copy() {
    return store.get(nextPresent()).orElseThrow().toArray(ValueLayout.JAVA_BYTE);
  }

  @Benchmark
  public boolean miss() {
    cursor = (cursor + 1) & (absent.length - 1);
    return store.get(absent[cursor]).isPresent();
  }
}
