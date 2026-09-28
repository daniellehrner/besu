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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
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
 * Runs whole programs through {@link EVM#runToHalt}, so that the dispatch, the gas and stack checks
 * and the frame traffic around each operation are measured together with the operation.
 *
 * <p>Every fork first runs all programs, so the interpreter loop is compiled against a mixed
 * profile as it is in production; measuring one program in a fork of its own would let the JIT
 * specialise the loop for that program alone. Run with {@code -Devm.bench.countOps=true} to print
 * the number of operations each program executes, to turn the score into a per-operation figure.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EvmLoopBenchmark {

  /** The programs; the synthetic ones loop until they run out of gas. */
  public enum Program {
    /** WETH9 transfer between two funded accounts: dispatch, two SLOADs, two SSTOREs, a LOG3. */
    WETH_TRANSFER,
    /** USDT balanceOf: a large pre-0.5 Solidity contract, one SLOAD. */
    USDT_BALANCE,
    /** Recursive Fibonacci from the vmPerformance reference tests: calls, jumps, stack. */
    FIB,
    /** Ackermann from the vmPerformance reference tests. */
    ACKERMANN,
    /** 256-bit multiplication loop from the vmPerformance reference tests. */
    MUL_LOOP,
    /** PUSH1..PUSH32 each followed by POP. */
    PUSH_POP,
    /** DUP and SWAP shuffles over a four item stack. */
    DUP_SWAP,
    /** Nothing but JUMPDEST, the cheapest operation. */
    JUMPDEST_SPAM,
    /** A counting loop of cheap arithmetic, comparisons, shifts and a conditional jump. */
    ARITH,
    /** MLOAD and MSTORE of already expanded memory. */
    MEMORY,
    /** A single STOP, the fixed cost of an execution. */
    STOP
  }

  private static final Address RECEIVER = Address.fromHexString("0x1000");
  private static final Address SENDER = Address.fromHexString("0x2000");
  private static final long SYNTHETIC_GAS = 3_000_000L;
  private static final int WARMUP_ROUNDS = 300;

  /** A tracer that asks for every hook and does nothing with them. */
  private static final OperationTracer ENABLED_NO_OP = new OperationTracer() {};

  @Param({
    "WETH_TRANSFER",
    "USDT_BALANCE",
    "FIB",
    "ACKERMANN",
    "MUL_LOOP",
    "PUSH_POP",
    "DUP_SWAP",
    "JUMPDEST_SPAM",
    "ARITH",
    "MEMORY",
    "STOP"
  })
  public String program;

  /** Whether to run with a tracer, which is how blocks are built and transactions traced. */
  @Param({"false"})
  public boolean traced;

  /** Whether to run the EVM v2 interpreter rather than the standard one. */
  @Param({"false", "true"})
  public boolean v2;

  private EVMExecutor executor;

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    final EvmConfiguration configuration =
        new EvmConfiguration(32_000L, EvmConfiguration.WorldUpdaterMode.STACKED, true, v2);
    final Map<Program, EVMExecutor> executors = new EnumMap<>(Program.class);
    for (final Program p : Program.values()) {
      executors.put(
          p, executor(p, configuration, traced ? ENABLED_NO_OP : OperationTracer.NO_TRACING));
    }
    for (int round = 0; round < WARMUP_ROUNDS; round++) {
      for (final Program p : Program.values()) {
        if (round % 10 == 0 || !isLong(p)) {
          executors.get(p).execute();
        }
      }
    }
    executor = executors.get(Program.valueOf(program));
    if (Boolean.getBoolean("evm.bench.countOps")) {
      countOps(Program.valueOf(program), configuration);
    }
  }

  @Benchmark
  public Bytes run() {
    return executor.execute();
  }

  private static boolean isLong(final Program p) {
    return switch (p) {
      case PUSH_POP, DUP_SWAP, JUMPDEST_SPAM, ARITH, MEMORY, MUL_LOOP -> true;
      default -> false;
    };
  }

  private static EVMExecutor executor(
      final Program p, final EvmConfiguration configuration, final OperationTracer tracer)
      throws IOException {
    final SimpleWorld world = new SimpleWorld();
    world.createAccount(SENDER, 0, Wei.of(1_000_000_000_000_000_000L));
    Bytes code;
    Bytes callData = Bytes.EMPTY;
    long gas = SYNTHETIC_GAS;
    switch (p) {
      case WETH_TRANSFER -> {
        code = resource("weth.hex");
        // WETH9 keeps balanceOf in slot 3
        world
            .createAccount(RECEIVER, 0, Wei.ZERO)
            .setStorageValue(mappingSlot(SENDER, 3), UInt256.valueOf(1_000_000_000L));
        callData =
            Bytes.concatenate(
                Bytes.fromHexString("0xa9059cbb"),
                Bytes32.leftPad(Address.fromHexString("0x3000").getBytes()),
                Bytes32.leftPad(Bytes.of(1)));
        gas = 1_000_000L;
      }
      case USDT_BALANCE -> {
        code = resource("usdt.hex");
        world.createAccount(RECEIVER, 0, Wei.ZERO);
        callData =
            Bytes.concatenate(
                Bytes.fromHexString("0x70a08231"), Bytes32.leftPad(SENDER.getBytes()));
        gas = 1_000_000L;
      }
      case FIB -> {
        code = resource("perf_tester.hex");
        world.createAccount(RECEIVER, 0, Wei.ZERO);
        callData =
            Bytes.fromHexString(
                "0xc6c2ea170000000000000000000000000000000000000000000000000000000000000010");
        gas = 80_000_000L;
      }
      case ACKERMANN -> {
        code = resource("perf_tester.hex");
        world.createAccount(RECEIVER, 0, Wei.ZERO);
        callData =
            Bytes.fromHexString(
                "0xfaa073f8"
                    + "0000000000000000000000000000000000000000000000000000000000000003"
                    + "0000000000000000000000000000000000000000000000000000000000000002");
        gas = 80_000_000L;
      }
      case MUL_LOOP -> {
        code = resource("loop_mul.hex");
        world.createAccount(RECEIVER, 0, Wei.ZERO);
        callData =
            Bytes.fromHexString(
                "0xc4f8b9fb"
                    + "8edad8b55b1586805ea8c245d8c16b06a5102b791fc6eb60693731c0677bf501"
                    + "8edad8b55b1586805ea8c245d8c16b06a5102b791fc6eb60693731c0677bf501"
                    + "0000000000000000000000000000000000000000000000000000000000002710");
        gas = 80_000_000L;
      }
      case PUSH_POP -> code = pushPopLoop();
      case DUP_SWAP -> code = dupSwapLoop();
      case JUMPDEST_SPAM -> code = jumpDestLoop();
      case ARITH -> code = arithLoop();
      case MEMORY -> code = memoryLoop();
      case STOP -> {
        code = Bytes.of(0x00);
        gas = 1_000_000L;
      }
      default -> throw new IllegalArgumentException(p.name());
    }
    if (world.get(RECEIVER) == null) {
      world.createAccount(RECEIVER, 0, Wei.ZERO);
    }
    return new EVMExecutor(EvmSpec.evmSpec(EvmSpecVersion.OSAKA, BigInteger.ONE, configuration))
        .worldUpdater(world)
        .code(new Code(code))
        .callData(callData)
        .sender(SENDER)
        .receiver(RECEIVER)
        .gas(gas)
        .tracer(tracer);
  }

  private static UInt256 mappingSlot(final Address key, final int slot) {
    return UInt256.fromBytes(
        Hash.hash(
                Bytes.concatenate(Bytes32.leftPad(key.getBytes()), Bytes32.leftPad(Bytes.of(slot))))
            .getBytes());
  }

  private static Bytes pushPopLoop() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x5b); // JUMPDEST
    int fill = 0x11;
    for (final int size : new int[] {1, 1, 1, 1, 1, 1, 2, 2, 2, 4, 20, 32}) {
      out.write(0x5f + size);
      for (int i = 0; i < size; i++) {
        out.write(fill);
      }
      fill = (fill + 0x11) & 0xff;
      out.write(0x50); // POP
    }
    out.writeBytes(new byte[] {0x60, 0x00, 0x56}); // PUSH1 0; JUMP
    return Bytes.wrap(out.toByteArray());
  }

  private static Bytes dupSwapLoop() {
    return Bytes.fromHexString(
        "0x"
            + "6001600260036004" // PUSH1 1 .. PUSH1 4
            + "5b" // JUMPDEST at 8
            + "80" // DUP1
            + "82" // DUP3
            + "91" // SWAP2
            + "90" // SWAP1
            + "83" // DUP4
            + "92" // SWAP3
            + "50" // POP
            + "50" // POP
            + "81" // DUP2
            + "93" // SWAP4
            + "50" // POP
            + "90" // SWAP1
            + "50" // POP
            + "600856"); // PUSH1 8; JUMP
  }

  private static Bytes jumpDestLoop() {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < 200; i++) {
      out.write(0x5b); // JUMPDEST
    }
    out.writeBytes(new byte[] {0x60, 0x00, 0x56}); // PUSH1 0; JUMP
    return Bytes.wrap(out.toByteArray());
  }

  private static Bytes arithLoop() {
    return Bytes.fromHexString(
        "0x"
            + "6000" // PUSH1 0: counter
            + "6007" // PUSH1 7: accumulator
            + "5b" // JUMPDEST at 4
            + "600302" // PUSH1 3; MUL
            + "8101" // DUP2; ADD
            + "61ffff16" // PUSH2 0xffff; AND
            + "60021b" // PUSH1 2; SHL
            + "60011c" // PUSH1 1; SHR
            + "80600511" // DUP1; PUSH1 5; GT
            + "50" // POP
            + "9060010190" // SWAP1; PUSH1 1; ADD; SWAP1
            + "8160011403" // DUP2; PUSH1 1; EQ; SUB
            + "8162ffffff1015" // DUP2; PUSH3 0xffffff; LT; ISZERO
            + "600457" // PUSH1 4; JUMPI
            + "00"); // STOP
  }

  private static Bytes memoryLoop() {
    return Bytes.fromHexString(
        "0x"
            + "5b" // JUMPDEST at 0
            + "60005160010160005260" // PUSH1 0; MLOAD; PUSH1 1; ADD; PUSH1 0; MSTORE; PUSH1..
            + "20516002016020526040" // ..0x20; MLOAD; PUSH1 2; ADD; PUSH1 0x20; MSTORE; PUSH1..
            + "51600301604052" // ..0x40; MLOAD; PUSH1 3; ADD; PUSH1 0x40; MSTORE
            + "600056"); // PUSH1 0; JUMP
  }

  private static Bytes resource(final String name) throws IOException {
    try (InputStream in = EvmLoopBenchmark.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IOException("missing " + name);
      }
      return Bytes.fromHexString(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
  }

  private static void countOps(final Program p, final EvmConfiguration configuration)
      throws IOException {
    final long[] counts = new long[256];
    final OperationTracer counting =
        new OperationTracer() {
          @Override
          public void tracePreExecution(final MessageFrame frame) {
            counts[frame.getCurrentOperation().getOpcode() & 0xff]++;
          }
        };
    executor(p, configuration, counting).execute();
    long total = 0;
    for (final long c : counts) {
      total += c;
    }
    System.err.printf("%n[%s] ops=%d%n", p, total);
  }
}
