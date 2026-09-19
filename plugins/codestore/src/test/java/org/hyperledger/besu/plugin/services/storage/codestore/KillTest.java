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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.plugin.services.storage.codestore.CodeStoreTest.bytes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A child JVM writes continuously and is SIGKILLed at a random moment, over and over on the same
 * store. After every kill: each record covered by a sync the child reported must be present and
 * intact, the records present must be exactly a prefix of what was written, and the log must scan
 * clean end to end.
 */
class KillTest {

  @TempDir Path dir;

  @Test
  @Tag("slow")
  void survivesRepeatedSigkill() throws Exception {
    final int iterations = Integer.getInteger("codestore.kill.iterations", 50);
    final long seed = System.nanoTime();
    final SplittableRandom rng = new SplittableRandom(seed);
    System.out.printf("KillTest: %d iterations, seed %d%n", iterations, seed);

    long next = 0;
    int tornTails = 0;
    int reindexed = 0;
    for (int iteration = 1; iteration <= iterations; iteration++) {
      final long lastSynced = writeUntilKilled(seed, next, rng.nextInt(1_200));

      try (CodeStore store = CodeStore.open(dir, KillTestWriter.OPTIONS)) {
        long present = next;
        while (store.contains(TestData.hash(seed, present))) {
          present++;
        }
        assertThat(present).as("records surviving kill %d", iteration).isGreaterThan(lastSynced);
        for (long i = present; i < present + 64; i++) {
          assertThat(store.contains(TestData.hash(seed, i)))
              .as("record %d past the end", i)
              .isFalse();
        }
        assertThat(store.entries()).isEqualTo(present);
        assertThat(store.verify()).as("records in a log with no torn record").isEqualTo(present);
        for (long i = 0; i < present; i++) {
          assertThat(bytes(store.get(TestData.hash(seed, i)).orElseThrow()))
              .as("record %d after kill %d", i, iteration)
              .isEqualTo(TestData.code(seed, i));
        }
        tornTails += store.truncatedBytesOnOpen() > 0 ? 1 : 0;
        reindexed += store.recoveredRecordsOnOpen() > 0 ? 1 : 0;
        System.out.printf(
            "kill %2d: synced through %,d, %,d records intact, reindexed %,d, truncated %,d bytes%n",
            iteration,
            lastSynced,
            present,
            store.recoveredRecordsOnOpen(),
            store.truncatedBytesOnOpen());
        next = present;
      }
    }
    System.out.printf(
        "KillTest: %d kills, %,d records, %d torn tails truncated, %d tail re-indexes, no loss%n",
        iterations, next, tornTails, reindexed);
  }

  /** Returns the index of the last record covered by a sync the child reported before dying. */
  private long writeUntilKilled(final long seed, final long start, final int killAfterMillis)
      throws Exception {
    final Process child =
        new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                "-cp",
                System.getProperty("java.class.path"),
                KillTestWriter.class.getName(),
                dir.toString(),
                Long.toString(seed),
                Long.toString(start))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    final AtomicLong lastSynced = new AtomicLong(start - 1);
    final CountDownLatch firstSync = new CountDownLatch(1);
    final StringBuilder noise = new StringBuilder();
    final Thread reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  try (BufferedReader in =
                      new BufferedReader(
                          new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = in.readLine(); line != null; line = in.readLine()) {
                      if (line.startsWith("S ")) {
                        lastSynced.set(Long.parseLong(line.substring(2)));
                        firstSync.countDown();
                      } else if (!line.equals("OPEN")) {
                        noise.append(line).append('\n');
                      }
                    }
                  } catch (final Exception e) {
                    noise.append(e);
                  }
                });

    assertThat(firstSync.await(60, TimeUnit.SECONDS))
        .as("child reached its first sync; output: %s", noise)
        .isTrue();
    Thread.sleep(killAfterMillis);
    assertThat(child.isAlive()).as("child still writing; output: %s", noise).isTrue();
    child.destroyForcibly(); // SIGKILL
    child.waitFor();
    reader.join();
    assertThat(noise.toString()).as("unexpected child output").isEmpty();
    return lastSynced.get();
  }
}
