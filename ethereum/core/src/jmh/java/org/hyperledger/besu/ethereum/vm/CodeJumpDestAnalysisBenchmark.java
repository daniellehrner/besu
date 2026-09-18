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
package org.hyperledger.besu.ethereum.vm;

import org.hyperledger.besu.evm.Code;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures {@link Code#isJumpDestInvalid(int)} across code shapes that stress the jump destination
 * analysis differently.
 *
 * <p>The cold benchmark builds a new {@link Code} per invocation because the analysis is memoised
 * per instance: a code hash that misses every cache pays the full analysis on its first JUMP, which
 * is the cost an attacker controls.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class CodeJumpDestAnalysisBenchmark {

  private static final int JUMPDEST = 0x5b;
  private static final int MAX_CODE_SIZE_GLAMSTERDAM = 65536;
  private static final int MAX_CODE_SIZE_MAINNET = 24576;

  /**
   * ATTACK_ALL_JUMPDEST: the glamsterdam devnet-8 spam contract, verbatim. `PUSH2 0xffff; JUMP`
   * followed by 64KB of JUMPDEST, with the contract's own address embedded so that every deployed
   * copy has a distinct code hash and therefore misses every cache.
   *
   * <p>ATTACK_NON_JUMPDEST_TARGET: the same contract jumping at a byte that is not a JUMPDEST, so
   * the destination can be rejected without consulting the analysis at all.
   *
   * <p>ATTACK_STOP_EVERY_64 / ATTACK_PUSH1_EVERY_8: the same contract with one STOP per 64 bytes,
   * respectively one PUSH1 per 8 bytes, which is what an attacker would deploy against an analysis
   * that only recognises unbroken runs of JUMPDEST.
   *
   * <p>PUSH1_DENSE_GLAMSTERDAM: nothing but `PUSH1 0x5b`, the largest possible number of PUSH
   * instructions in 64KB.
   *
   * <p>PUSH32_DENSE_*: a chain of PUSH32, the opposite extreme — almost every byte is immediate
   * data and almost none is a jump destination.
   *
   * <p>MIXED_MAINNET: arithmetic, small pushes and occasional jump destinations, closer to ordinary
   * compiled output.
   *
   * <p>SOLIDITY_13K: a real compiled Solidity contract from a devnet, 13,199 bytes with 2,378 PUSH
   * and 465 JUMPDEST instructions.
   */
  @Param({
    "ATTACK_ALL_JUMPDEST",
    "ATTACK_NON_JUMPDEST_TARGET",
    "ATTACK_STOP_EVERY_64",
    "ATTACK_PUSH1_EVERY_8",
    "PUSH1_DENSE_GLAMSTERDAM",
    "PUSH32_DENSE_GLAMSTERDAM",
    "PUSH32_DENSE_MAINNET",
    "MIXED_MAINNET",
    "SOLIDITY_13K",
    "SMALL"
  })
  private String caseName;

  private Bytes codeBytes;
  private int jumpDestination;
  private Code warmCode;

  @Setup
  public void setUp() {
    final byte[] code;
    switch (caseName) {
      case "ATTACK_ALL_JUMPDEST" -> {
        code = attackContract();
        jumpDestination = MAX_CODE_SIZE_GLAMSTERDAM - 1;
      }
      case "ATTACK_NON_JUMPDEST_TARGET" -> {
        code = attackContract();
        // STOP rather than JUMPDEST, so the destination is invalid on its opcode alone
        code[MAX_CODE_SIZE_GLAMSTERDAM - 1] = 0x00;
        jumpDestination = MAX_CODE_SIZE_GLAMSTERDAM - 1;
      }
      case "ATTACK_STOP_EVERY_64" -> {
        code = attackContract();
        for (int i = 64; i < MAX_CODE_SIZE_GLAMSTERDAM - 1; i += 64) {
          code[i] = 0x00;
        }
        jumpDestination = MAX_CODE_SIZE_GLAMSTERDAM - 1;
      }
      case "ATTACK_PUSH1_EVERY_8" -> {
        code = attackContract();
        for (int i = 64; i < MAX_CODE_SIZE_GLAMSTERDAM - 2; i += 8) {
          code[i] = 0x60;
        }
        jumpDestination = MAX_CODE_SIZE_GLAMSTERDAM - 1;
      }
      case "PUSH1_DENSE_GLAMSTERDAM" -> {
        code = new byte[MAX_CODE_SIZE_GLAMSTERDAM];
        for (int i = 0; i < MAX_CODE_SIZE_GLAMSTERDAM - 1; i += 2) {
          code[i] = 0x60;
          code[i + 1] = (byte) JUMPDEST;
        }
        code[MAX_CODE_SIZE_GLAMSTERDAM - 1] = (byte) JUMPDEST;
        jumpDestination = MAX_CODE_SIZE_GLAMSTERDAM - 1;
      }
      case "SOLIDITY_13K" -> {
        code = solidityContract();
        jumpDestination = lastJumpDest(code);
      }
      case "PUSH32_DENSE_GLAMSTERDAM" -> {
        code = pushDense(MAX_CODE_SIZE_GLAMSTERDAM);
        jumpDestination = code.length - 1;
      }
      case "PUSH32_DENSE_MAINNET" -> {
        code = pushDense(MAX_CODE_SIZE_MAINNET);
        jumpDestination = code.length - 1;
      }
      case "MIXED_MAINNET" -> {
        code = mixed(MAX_CODE_SIZE_MAINNET);
        jumpDestination = code.length - 1;
      }
      case "SMALL" -> {
        code = mixed(256);
        jumpDestination = code.length - 1;
      }
      default -> throw new IllegalArgumentException("unknown case " + caseName);
    }
    codeBytes = Bytes.wrap(code);
    warmCode = new Code(codeBytes);
    warmCode.isJumpDestInvalid(jumpDestination);
  }

  /** PUSH2 0xffff; JUMP; own address at offset 44; JUMPDEST everywhere else. */
  private static byte[] attackContract() {
    final byte[] code = new byte[MAX_CODE_SIZE_GLAMSTERDAM];
    java.util.Arrays.fill(code, (byte) JUMPDEST);
    code[0] = 0x61;
    code[1] = (byte) 0xff;
    code[2] = (byte) 0xff;
    code[3] = 0x56;
    final byte[] address =
        Bytes.fromHexString("0x8172765afc45126f1428f6070e749accdc2d7ba0").toArrayUnsafe();
    System.arraycopy(address, 0, code, 44, address.length);
    return code;
  }

  private static byte[] solidityContract() {
    try (InputStream in =
        CodeJumpDestAnalysisBenchmark.class.getResourceAsStream("solidity-contract-13k.hex")) {
      return Bytes.fromHexString(new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim())
          .toArrayUnsafe();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The last JUMPDEST that is an instruction, not immediate data. */
  private static int lastJumpDest(final byte[] code) {
    int last = -1;
    for (int pc = 0; pc < code.length; ) {
      final int opcode = code[pc] & 0xff;
      if (opcode == JUMPDEST) {
        last = pc;
      }
      pc += (opcode >= 0x60 && opcode <= 0x7f) ? opcode - 0x5e : 1;
    }
    return last;
  }

  /** A chain of PUSH32, ending in a JUMPDEST so the final byte is a valid destination. */
  private static byte[] pushDense(final int size) {
    final byte[] code = new byte[size];
    int i = 0;
    while (i + 33 < size) {
      code[i] = (byte) 0x7f;
      i += 33;
    }
    while (i < size) {
      code[i++] = (byte) JUMPDEST;
    }
    return code;
  }

  /** ADD/MUL/PUSH1/PUSH2/DUP with a JUMPDEST every 32 bytes, ending in a JUMPDEST. */
  private static byte[] mixed(final int size) {
    final byte[] pattern = {
      0x60,
      0x01,
      0x60,
      0x02,
      0x01,
      (byte) 0x80,
      0x02,
      0x61,
      0x11,
      0x22,
      0x03,
      (byte) 0x90,
      0x04,
      0x50,
      0x60,
      0x40,
      0x51,
      0x60,
      0x20,
      0x52,
      0x01,
      0x03,
      (byte) 0x80,
      0x50
    };
    final byte[] code = new byte[size];
    for (int i = 0; i < size; i++) {
      code[i] = (i % 32 == 31) ? (byte) JUMPDEST : pattern[i % pattern.length];
    }
    code[size - 1] = (byte) JUMPDEST;
    return code;
  }

  /**
   * Analyses the code from scratch and checks the destination.
   *
   * @return whether the destination is invalid
   */
  @Benchmark
  public boolean coldAnalysis() {
    return new Code(codeBytes).isJumpDestInvalid(jumpDestination);
  }

  /**
   * Checks the destination against an analysis already performed.
   *
   * @return whether the destination is invalid
   */
  @Benchmark
  public boolean warmLookup() {
    return warmCode.isJumpDestInvalid(jumpDestination);
  }
}
