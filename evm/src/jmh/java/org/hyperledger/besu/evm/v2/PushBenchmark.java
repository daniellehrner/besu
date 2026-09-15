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
package org.hyperledger.besu.evm.v2;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.EvmSpec;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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
 * Runs whole programs through the EVM v2 loop so that a change to one opcode is measured with the
 * dispatch, gas and frame traffic around it. The synthetic programs loop over PUSH/POP pairs until
 * they run out of gas; the real ones are single calls into mainnet contract code. The op count of
 * each program is printed once at setup so the score can be turned into a per-op figure.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class PushBenchmark {

  private static final Address RECEIVER = Address.fromHexString("0x1000");
  private static final long SYNTHETIC_GAS = 3_000_000L;

  @Param({"MIX", "PUSH1", "PUSH2", "PUSH20", "PUSH32", "WETH_BALANCE", "USDT_BALANCE", "STOP"})
  public String program;

  private EVMExecutor executor;

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    final Bytes code;
    final Bytes callData;
    final long gas;
    switch (program) {
      case "MIX" -> {
        code = pushLoop(1, 1, 1, 1, 1, 1, 2, 2, 2, 4, 20, 32);
        callData = Bytes.EMPTY;
        gas = SYNTHETIC_GAS;
      }
      case "PUSH1" -> {
        code = pushLoop(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        callData = Bytes.EMPTY;
        gas = SYNTHETIC_GAS;
      }
      case "PUSH2" -> {
        code = pushLoop(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
        callData = Bytes.EMPTY;
        gas = SYNTHETIC_GAS;
      }
      case "PUSH20" -> {
        code = pushLoop(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20);
        callData = Bytes.EMPTY;
        gas = SYNTHETIC_GAS;
      }
      case "PUSH32" -> {
        code = pushLoop(32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32);
        callData = Bytes.EMPTY;
        gas = SYNTHETIC_GAS;
      }
      case "WETH_BALANCE" -> {
        code = resource("weth.hex");
        callData = balanceOf();
        gas = 1_000_000L;
      }
      case "USDT_BALANCE" -> {
        code = resource("usdt.hex");
        callData = balanceOf();
        gas = 1_000_000L;
      }
      case "STOP" -> {
        code = Bytes.of(0x00);
        callData = Bytes.EMPTY;
        gas = 1_000_000L;
      }
      default -> throw new IllegalArgumentException(program);
    }
    final EvmConfiguration configuration =
        new EvmConfiguration(32_000L, EvmConfiguration.WorldUpdaterMode.STACKED, true, true);
    executor =
        new EVMExecutor(EvmSpec.evmSpec(EvmSpecVersion.CANCUN, BigInteger.ONE, configuration))
            .code(new Code(code))
            .callData(callData)
            .receiver(RECEIVER)
            .gas(gas);
    countOps(code, callData, gas, configuration);
  }

  @Benchmark
  public Bytes run() {
    return executor.execute();
  }

  private static Bytes pushLoop(final int... sizes) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x5b); // JUMPDEST
    int fill = 0x11;
    for (final int size : sizes) {
      out.write(0x5f + size);
      for (int i = 0; i < size; i++) {
        out.write(fill);
      }
      fill = (fill + 0x11) & 0xff;
      out.write(0x50); // POP
    }
    out.write(0x61); // PUSH2 0x0000
    out.write(0x00);
    out.write(0x00);
    out.write(0x56); // JUMP
    return Bytes.wrap(out.toByteArray());
  }

  private static Bytes balanceOf() {
    return Bytes.concatenate(
        Bytes.fromHexString("0x70a08231"), Bytes32Padding.leftPad(RECEIVER.getBytes()));
  }

  private static Bytes resource(final String name) throws IOException {
    try (InputStream in = PushBenchmark.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IOException("missing " + name);
      }
      return Bytes.fromHexString(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
  }

  private void countOps(
      final Bytes code, final Bytes callData, final long gas, final EvmConfiguration config) {
    final long[] counts = new long[256];
    final OperationTracer counting =
        new OperationTracer() {
          @Override
          public void tracePreExecution(final MessageFrame frame) {
            counts[frame.getCurrentOperation().getOpcode() & 0xff]++;
          }
        };
    new EVMExecutor(EvmSpec.evmSpec(EvmSpecVersion.CANCUN, BigInteger.ONE, config))
        .code(new Code(code))
        .callData(callData)
        .receiver(RECEIVER)
        .gas(gas)
        .tracer(counting)
        .execute();
    long total = 0;
    long pushes = 0;
    for (int op = 0; op < 256; op++) {
      total += counts[op];
      if (op >= 0x60 && op <= 0x7f) {
        pushes += counts[op];
      }
    }
    System.err.printf(
        "%n[%s] ops=%d pushes=%d (%.1f%%)%n",
        program, total, pushes, 100.0 * pushes / Math.max(1, total));
  }

  /** Left pads to 32 bytes; kept local so the benchmark has no dependency beyond tuweni. */
  private static final class Bytes32Padding {
    static Bytes leftPad(final Bytes value) {
      final byte[] out = new byte[32];
      System.arraycopy(value.toArrayUnsafe(), 0, out, 32 - value.size(), value.size());
      return Bytes.wrap(out);
    }
  }
}
