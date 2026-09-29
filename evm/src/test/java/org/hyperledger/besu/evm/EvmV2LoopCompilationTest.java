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
package org.hyperledger.besu.evm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the untraced v2 loop in a JVM of its own until C2 compiles it, and checks C2's report of
 * what it inlined into the loop. {@link EvmLoopMethodSizeTest} checks the sizes that decide
 * inlining from the bytecode; only C2 knows whether the whole loop stayed within its budget, as
 * once the loop and what is inlined into it reach 8000 bytes of bytecode, C2 inlines nothing more.
 */
class EvmV2LoopCompilationTest {

  private static final String LOOP = "org.hyperledger.besu.evm.EVM::runToHaltV2Untraced";

  /**
   * Calls of {@link EvmLoopMethodSizeTest#NOT_INLINED} that the report names by the class the
   * profile saw rather than by the class the bytecode names.
   */
  private static final Set<String> NOT_INLINED_AS_PROFILED =
      Set.of("ArrayWrappingBytes.toArrayUnsafe");

  /** One call site in C2's report: the callee and what C2 decided. */
  private static final Pattern CALL =
      Pattern.compile("@ \\d+\\s+(\\S+)::(\\S+) \\(\\d+ bytes\\)\\s+(.*)$");

  @Test
  void c2InlinesEverythingTheUntracedLoopCalls(@TempDir final Path dir) throws Exception {
    final Path directives = dir.resolve("directives.json");
    Files.writeString(directives, "[{match: \"" + LOOP + "\", c2: {PrintInlining: true}}]", UTF_8);
    final Path output = dir.resolve("output.txt");
    final Process process =
        new ProcessBuilder(
                ProcessHandle.current()
                    .info()
                    .command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                "-cp",
                System.getProperty("java.class.path"),
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:CompilerDirectivesFile=" + directives,
                // waits for each compilation, so that the loop's is done before the JVM exits
                "-Xbatch",
                Workload.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).isTrue();
    final List<String> lines = Files.readAllLines(output, UTF_8);
    assertThat(process.exitValue()).as(String.join("\n", lines)).isZero();

    final List<String> calls = new ArrayList<>();
    final List<String> notInlined = new ArrayList<>();
    // the report indents a callee's calls below it; what the general path calls does not matter
    int skipBelow = Integer.MAX_VALUE;
    for (final String line : lines) {
      final Matcher call = CALL.matcher(line);
      if (!call.find()) {
        continue;
      }
      calls.add(line);
      final int depth = call.start();
      if (depth > skipBelow) {
        continue;
      }
      skipBelow = Integer.MAX_VALUE;
      final String callee =
          call.group(1).substring(call.group(1).lastIndexOf('.') + 1) + "." + call.group(2);
      if (EvmLoopMethodSizeTest.NOT_INLINED.contains(callee)
          || NOT_INLINED_AS_PROFILED.contains(callee)) {
        skipBelow = depth;
      } else if (!inlined(call.group(3))) {
        notInlined.add(line.strip());
      }
    }
    assertThat(calls).as("C2's report on %s", LOOP).isNotEmpty();
    assertThat(notInlined)
        .as(
            "calls in %s that C2 did not inline; each makes C2 keep the loop's state in memory"
                + " around it. The whole report:%n%s",
            LOOP, String.join("\n", calls))
        .isEmpty();
  }

  private static boolean inlined(final String decision) {
    if (decision.contains("failed")) {
      // a call the profile never saw run is a trap until it runs, not a call
      return decision.endsWith("never executed");
    }
    return decision.contains("inline")
        || decision.contains("intrinsic")
        || decision.equals("accessor");
  }

  /**
   * Runs one program that reaches every inline case of the loop, and the general path, until C2 has
   * compiled the loop.
   */
  public static final class Workload {

    private Workload() {}

    /**
     * Runs the program.
     *
     * @param args unused
     */
    public static void main(final String[] args) {
      final Address sender = Address.fromHexString("0x2000");
      final Address receiver = Address.fromHexString("0x1000");
      final SimpleWorld world = new SimpleWorld();
      world.createAccount(sender, 0, Wei.of(1_000_000_000L));
      world.createAccount(receiver, 0, Wei.ZERO);
      final EvmConfiguration configuration =
          new EvmConfiguration(32_000L, EvmConfiguration.WorldUpdaterMode.STACKED, true, true);
      final EVMExecutor executor =
          new EVMExecutor(EvmSpec.evmSpec(EvmSpecVersion.OSAKA, BigInteger.ONE, configuration))
              .worldUpdater(world)
              .code(new Code(program()))
              .callData(Bytes.fromHexString("0x0102030405060708090a"))
              .sender(sender)
              .receiver(receiver)
              .gas(10_000_000L)
              .tracer(OperationTracer.NO_TRACING);
      for (int i = 0; i < 2_000; i++) {
        executor.execute();
      }
    }

    /**
     * A loop over one use of every inline case, some general-path operations, and the jump
     * variants.
     */
    static Bytes program() {
      final Assembler a = new Assembler();
      a.hex("6101005b"); // PUSH2 0x100, the counter, then the head of the loop
      final int loop = 3;
      a.hex("600760050150 600760050350 600360090250 600360090450 600360090650");
      a.hex("600160021650 600160021750 600160021850");
      a.hex("600160021450600160021050600160021150600160021250600160021350");
      a.hex("6001600a1b506001600a1c506001600a1d50");
      a.hex("60011550 601919 50 60ff60000b50 5f50 5a50 3650 60003550");
      a.hex("602a600052 60005150 630102030450");
      a.hex("7f" + "11".repeat(32) + "50");
      a.hex("808080 90 91 81 50505050"); // DUP1 x3, SWAP1, SWAP2, DUP2, POP x4
      a.hex("602060002050"); // KECCAK256, through the general path
      a.hex("60005450"); // SLOAD, through the general path
      // PUSH2 JUMP to the next instruction, which is a JUMPDEST
      a.push2(a.size() + 4).hex("565b");
      // PUSH2 JUMPI, not taken
      a.hex("6000").push2(loop).hex("57");
      // JUMP and JUMPI taken, with the destination through the stack
      a.push2(a.size() + 6).hex("8050565b");
      a.hex("6001").push2(a.size() + 6).hex("8050575b");
      // JUMPI not taken, with the destination through the stack
      a.hex("6000").push2(loop).hex("805057");
      // counter - 1, and around again while it is not zero
      a.hex("60019003 80").push2(loop).hex("57 50 00");
      return Bytes.wrap(a.bytes());
    }
  }

  private static final class Assembler {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Assembler hex(final String hex) {
      final byte[] bytes = HexFormat.of().parseHex(hex.replace(" ", ""));
      out.write(bytes, 0, bytes.length);
      return this;
    }

    Assembler push2(final int value) {
      out.write(0x61);
      out.write(value >>> 8);
      out.write(value & 0xff);
      return this;
    }

    int size() {
      return out.size();
    }

    byte[] bytes() {
      return out.toByteArray();
    }
  }
}
