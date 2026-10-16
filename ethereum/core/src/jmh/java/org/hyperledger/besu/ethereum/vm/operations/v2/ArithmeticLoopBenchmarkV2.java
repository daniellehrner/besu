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
package org.hyperledger.besu.ethereum.vm.operations.v2;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Runs each UInt256-based EVM v2 operation 10,000 times through the v2 interpreter loop, on
 * full-width operands that the program pushes once and copies with DUP for every use. Before it
 * measures, the loop runs a program with every case, so that C2 compiles it with all of them hot,
 * as block processing does. {@link #gasUsed} is the gas of one execution: MGas/s = gasUsed / (ns
 * per op) * 1e3. EMPTY, a program that only stops, is the executor's fixed cost per execution.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ArithmeticLoopBenchmarkV2 {

  private static final int REPEATS = 10_000;
  private static final int MIXED_ROUNDS = 200;
  private static final long GAS = 100_000_000L;

  private static final String A_256 =
      "8f4e3d2c1b0a99887766554433221100ffeeddccbbaa99887766554433221101";
  private static final String B_256 =
      "7a6b5c4d3e2f10010203040506070809a1b2c3d4e5f60718293a4b5c6d7e8f91";
  private static final String M_256 =
      "6d5c4b3a29180706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a09f8d";
  private static final String B_192 =
      "0000000000000000a1b2c3d4e5f60718293a4b5c6d7e8f9101020304050607f1";
  private static final String E18 =
      "0000000000000000000000000000000000000000000000000de0b6b3a7640000";
  private static final String M_64 =
      "0000000000000000000000000000000000000000000000001fffffffffffffff";
  private static final String NEG_256 =
      "f0e1d2c3b4a5968778695a4b3c2d1e0f00112233445566778899aabbccddeeff";
  private static final String SHIFT_77 =
      "000000000000000000000000000000000000000000000000000000000000004d";
  private static final String EXP_255 =
      "00000000000000000000000000000000000000000000000000000000000000ff";

  /** One case: its body, and the operands a (top), b and m it runs on. */
  private record Case(String body, String a, String b, String m) {}

  private static final String BINARY = "8181%s50"; // DUP2 DUP2 op POP
  private static final String TERNARY = "828282%s50"; // DUP3 DUP3 DUP3 op POP

  private static final Map<String, Case> CASES = new LinkedHashMap<>();

  static {
    CASES.put("ADD", new Case(BINARY.formatted("01"), A_256, B_256, M_256));
    CASES.put("MUL", new Case(BINARY.formatted("02"), A_256, B_256, M_256));
    CASES.put("SUB", new Case(BINARY.formatted("03"), A_256, B_256, M_256));
    CASES.put("DIV_BY64", new Case(BINARY.formatted("04"), A_256, E18, M_256));
    CASES.put("DIV_BY192", new Case(BINARY.formatted("04"), A_256, B_192, M_256));
    CASES.put("SDIV_BY64", new Case(BINARY.formatted("05"), NEG_256, E18, M_256));
    CASES.put("MOD_BY64", new Case(BINARY.formatted("06"), A_256, E18, M_256));
    CASES.put("MOD_BY192", new Case(BINARY.formatted("06"), A_256, B_192, M_256));
    CASES.put("SMOD_BY64", new Case(BINARY.formatted("07"), NEG_256, E18, M_256));
    CASES.put("ADDMOD_256", new Case(TERNARY.formatted("08"), A_256, B_256, M_256));
    CASES.put("ADDMOD_64", new Case(TERNARY.formatted("08"), A_256, B_256, M_64));
    CASES.put("MULMOD_256", new Case(TERNARY.formatted("09"), A_256, B_256, M_256));
    CASES.put("MULMOD_64", new Case(TERNARY.formatted("09"), A_256, B_256, M_64));
    CASES.put("EXP", new Case(BINARY.formatted("0a"), A_256, EXP_255, M_256));
    CASES.put("AND", new Case(BINARY.formatted("16"), A_256, B_256, M_256));
    CASES.put("OR", new Case(BINARY.formatted("17"), A_256, B_256, M_256));
    CASES.put("XOR", new Case(BINARY.formatted("18"), A_256, B_256, M_256));
    CASES.put("NOT", new Case("801950", A_256, B_256, M_256)); // DUP1 NOT POP
    CASES.put("SHL", new Case(BINARY.formatted("1b"), SHIFT_77, NEG_256, M_256));
    CASES.put("SHR", new Case(BINARY.formatted("1c"), SHIFT_77, NEG_256, M_256));
    CASES.put("SAR", new Case(BINARY.formatted("1d"), SHIFT_77, NEG_256, M_256));
    CASES.put("PUSH32", new Case("7f" + B_256 + "50", A_256, B_256, M_256)); // PUSH32 POP
    // no UInt256 operation: what the loop costs around one
    CASES.put("DUP_CONTROL", new Case("81815050", A_256, B_256, M_256)); // DUP2 DUP2 POP POP
  }

  @Param({
    "ADD",
    "MUL",
    "SUB",
    "DIV_BY64",
    "DIV_BY192",
    "SDIV_BY64",
    "MOD_BY64",
    "MOD_BY192",
    "SMOD_BY64",
    "ADDMOD_256",
    "ADDMOD_64",
    "MULMOD_256",
    "MULMOD_64",
    "EXP",
    "AND",
    "OR",
    "XOR",
    "NOT",
    "SHL",
    "SHR",
    "SAR",
    "PUSH32",
    "DUP_CONTROL",
    "EMPTY",
    "MIXED"
  })
  public String caseName;

  private EVMExecutor executor;
  private Code code;

  /** Gas per execution of the measured program, written to the console once. */
  public long gasUsed;

  @Setup(Level.Trial)
  public void setUp() {
    final Address sender = Address.fromHexString("0x2000");
    final Address receiver = Address.fromHexString("0x1000");
    final SimpleWorld world = new SimpleWorld();
    world.createAccount(sender, 0, Wei.of(1_000_000_000L));
    world.createAccount(receiver, 0, Wei.ZERO);
    final EvmConfiguration v2 =
        new EvmConfiguration(32_000L, EvmConfiguration.WorldUpdaterMode.STACKED, true, true);
    executor =
        new EVMExecutor(EvmSpec.evmSpec(EvmSpecVersion.OSAKA, BigInteger.ONE, v2))
            .worldUpdater(world)
            .callData(Bytes.EMPTY)
            .sender(sender)
            .receiver(receiver)
            .gas(GAS)
            .tracer(OperationTracer.NO_TRACING);

    // every case hot before the measured one, as in block processing
    final Code mixed = new Code(mixedProgram());
    for (int i = 0; i < 300; i++) {
      executor.code(mixed);
      executor.execute();
    }

    code =
        new Code(
            switch (caseName) {
              case "MIXED" -> mixedProgram();
              case "EMPTY" -> Bytes.of(0);
              default -> program(CASES.get(caseName));
            });
    executor.code(code);
    // one traced run for the gas, then the measured runs untraced
    final long[] remaining = new long[1];
    executor.tracer(
        new OperationTracer() {
          @Override
          public void traceContextExit(final MessageFrame frame) {
            remaining[0] = frame.getRemainingGas();
          }
        });
    executor.execute();
    executor.tracer(OperationTracer.NO_TRACING);
    gasUsed = GAS - remaining[0];
    System.out.printf("%n[gas] %s %d%n", caseName, gasUsed);
  }

  @Benchmark
  public Bytes run() {
    return executor.execute();
  }

  private static String setup(final Case c) {
    // PUSH32 m, PUSH32 b, PUSH32 a: a on top
    return "7f" + c.m() + "7f" + c.b() + "7f" + c.a();
  }

  private static Bytes program(final Case c) {
    return Bytes.fromHexString(setup(c) + c.body().repeat(REPEATS) + "00");
  }

  /** Every case in turn, each on its own operands, MIXED_ROUNDS times. */
  private static Bytes mixedProgram() {
    final StringBuilder code = new StringBuilder();
    for (int i = 0; i < MIXED_ROUNDS; i++) {
      for (final Case c : CASES.values()) {
        // push this case's operands, run its body three times, drop them
        code.append(setup(c)).append(c.body().repeat(3)).append("505050");
      }
    }
    return Bytes.fromHexString(code + "00");
  }
}
