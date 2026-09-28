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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.testutils.TestMessageFrameBuilder;
import org.hyperledger.besu.evm.toy.ToyWorld;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the same programs through the untraced EVM v2 interpreter loop, which keeps its state in
 * locals and executes the cheap operations inline, and through the traced loop, which executes
 * every operation through its implementation, and requires the two to end in exactly the same
 * state.
 */
class InterpreterLoopDifferentialTest {

  private static final Address CONTRACT = TestMessageFrameBuilder.DEFAULT_ADDRESS;
  private static final Address CALLEE = Address.fromHexString("0xca11ee");
  // Callee code: PUSH1 1, PUSH1 0, SSTORE, PUSH1 0x2a, PUSH1 0, MSTORE, PUSH1 0x20, PUSH1 0, RETURN
  private static final Bytes CALLEE_CODE = Bytes.fromHexString("0x6001600055602a60005260206000f3");

  private static final BlockValues BLOCK_VALUES =
      new BlockValues() {
        @Override
        public Bytes getDifficultyBytes() {
          return Bytes.of(0x02, 0x00);
        }

        @Override
        public Bytes32 getMixHashOrPrevRandao() {
          return Bytes32.fromHexStringLenient("0x7a4d");
        }

        @Override
        public Optional<Wei> getBaseFee() {
          return Optional.of(Wei.of(7));
        }

        @Override
        public long getNumber() {
          return 1337;
        }

        @Override
        public long getTimestamp() {
          return 1_700_000_000L;
        }

        @Override
        public long getGasLimit() {
          return 60_000_000L;
        }
      };

  /**
   * Records the outermost frame's stack as the frame completes, which is the last moment it can be
   * read: a v2 frame hands its stack back to the pool right after. Enabled, it sends execution
   * through the traced loop; disabled, through the untraced one, as for block import.
   */
  private static final class StackCapture implements OperationTracer {
    private final boolean enabled;
    private List<String> stack = List.of();

    StackCapture(final boolean enabled) {
      this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
      return enabled;
    }

    @Override
    public void traceContextExit(final MessageFrame frame) {
      if (frame.getDepth() == 0) {
        final List<String> items = new ArrayList<>();
        final long[] s = frame.stackDataV2();
        for (int i = 0; i < frame.stackTopV2(); i++) {
          items.add(
              String.format(
                  "%016x%016x%016x%016x",
                  s[i << 2], s[(i << 2) + 1], s[(i << 2) + 2], s[(i << 2) + 3]));
        }
        stack = items;
      }
    }
  }

  private static final boolean TRACED = true;
  private static final boolean UNTRACED = false;

  private record Fork(String name, Function<EvmConfiguration, EVM> factory) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static final List<Fork> FORKS =
      List.of(
          new Fork("frontier", MainnetEVMs::frontier),
          new Fork("byzantium", MainnetEVMs::byzantium),
          new Fork("constantinople", MainnetEVMs::constantinople),
          new Fork("london", MainnetEVMs::london),
          new Fork("shanghai", MainnetEVMs::shanghai),
          new Fork("cancun", MainnetEVMs::cancun),
          new Fork("osaka", c -> MainnetEVMs.osaka(BigInteger.ONE, c)),
          new Fork("amsterdam", MainnetEVMs::amsterdam));

  static Stream<Arguments> evms() {
    return FORKS.stream()
        .flatMap(
            fork ->
                Stream.of(true, false)
                    .map(
                        optimized ->
                            Arguments.of(
                                fork,
                                optimized,
                                fork.factory()
                                    .apply(
                                        new EvmConfiguration(
                                            32_000L,
                                            EvmConfiguration.WorldUpdaterMode.STACKED,
                                            optimized,
                                            true)))));
  }

  /** Programs aimed at the edges of the inline operations: stack limits, jumps, code end, gas. */
  private static final Map<String, String> EDGE_PROGRAMS =
      Map.ofEntries(
          Map.entry("dup16 underflow", repeat("6001", 15) + "8f"),
          Map.entry("dup16 exact", repeat("6001", 16) + "8f"),
          Map.entry("swap16 underflow", repeat("6001", 16) + "9f"),
          Map.entry("swap16 exact", repeat("6001", 17) + "9f"),
          Map.entry("pop empty", "50"),
          Map.entry("jump empty", "56"),
          Map.entry("jumpi one item", "600157"),
          Map.entry("push overflow", repeat("6001", 1025)),
          Map.entry("dup overflow", "6001" + repeat("80", 1024)),
          Map.entry("push0 overflow", repeat("5f", 1025)),
          Map.entry(
              "stack growth",
              repeat("6001", 300) + repeat("80", 100) + repeat("90", 50) + repeat("50", 400)),
          Map.entry("jump not jumpdest", "600356"),
          Map.entry("jump past code", "60ff56"),
          Map.entry("jump into push data", "600456605b5b"),
          Map.entry("jump wide destination", "64000000000856005b"),
          Map.entry("jump 32 byte destination", "7f" + "00".repeat(31) + "2356" + "00" + "5b"),
          Map.entry("jump negative int", "638000000056"),
          Map.entry("jumpi zero condition invalid destination", "600060ff57"),
          Map.entry("jumpi zero condition wide", "7f" + "00".repeat(32) + "60ff5700"),
          Map.entry("jumpi taken", "6001600657005b"),
          Map.entry("jumpi invalid taken", "6001600557005b"),
          Map.entry("loop", "6000" + "5b" + "600101" + "80" + "6010" + "11" + "600257"),
          Map.entry("truncated push2", "6101"),
          Map.entry("truncated push32", "7f0102"),
          Map.entry("push at end", "6001"),
          Map.entry("push0", "5f5f01"),
          Map.entry("implicit stop", "60016002"),
          Map.entry("call", "6000600060006000600062ca11ee61fffff1"),
          Map.entry("call then continue", "6000600060206000600062ca11ee61fffff15060005180"),
          Map.entry("return", "602a60005260206000f3"),
          Map.entry("revert", "602a60005260206000fd"),
          Map.entry("invalid", "fe"),
          Map.entry("jumpi on eq", "6001600114600957005b00"),
          Map.entry("jumpi on eq false", "6001600214600957005b00"),
          Map.entry("jumpi on 32 bytes", "7f" + "00".repeat(31) + "01" + "602557005b"),
          Map.entry("jumpi on 32 zero bytes", "7f" + "00".repeat(32) + "602557005b"),
          Map.entry("jumpi on callvalue", "3460065700005b"),
          Map.entry(
              "iszero kinds", "600115" + "7f" + "00".repeat(32) + "15" + "600160011415" + "3415"),
          Map.entry("jump push3 destination", "6200000656005b"),
          Map.entry("jump push4 destination", "630000000756005b"),
          Map.entry("jump computed destination", "600360040156005b"),
          Map.entry("eq across lengths", "6005" + "7f" + "00".repeat(31) + "05" + "14"),
          Map.entry("eq different", "6005" + "7f" + "01" + "00".repeat(30) + "05" + "14"),
          Map.entry("lt gt across lengths", "610100" + "60ff" + "10" + "610100" + "60ff" + "11"),
          Map.entry("lt gt equal", "6007600710" + "6007600711"),
          Map.entry("comparisons of zeros", "5f600014" + "6000" + "7f" + "00".repeat(32) + "10"),
          Map.entry("comparisons of eq results", "600160011460016002141460016001141011"),
          Map.entry("comparisons of callvalue", "3434143434103411"),
          Map.entry("push2 jump", "61000456" + "5b" + "00"),
          Map.entry("push2 jump not jumpdest", "61000556" + "5b" + "00"),
          Map.entry("push2 jump past code", "61ffff56"),
          Map.entry("push2 jump at code end", "61000356"),
          Map.entry("push2 jumpi taken", "6001" + "61000757" + "00" + "5b" + "00"),
          Map.entry("push2 jumpi not taken", "6000" + "61000757" + "00" + "5b" + "00"),
          Map.entry("push2 jumpi empty stack", "61000457" + "5b"),
          Map.entry("push2 jumpi on eq", "600160011461000957005b"),
          Map.entry("push2 jumpi on callvalue", "3461000757005b00"),
          Map.entry("jumpdest as last byte", "61000356" + "5b"),
          Map.entry("push2 truncated before jump", "610056"),
          Map.entry("push2 at code end", "610001"),
          Map.entry(
              "dispatcher",
              "60003560e01c" // PUSH1 0, CALLDATALOAD, PUSH1 0xe0, SHR
                  + "80630102030414" // DUP1, PUSH4 0x01020304, EQ
                  + "61001d57" // PUSH2 29, JUMPI
                  + "80630a0b0c0d14" // DUP1, PUSH4 0x0a0b0c0d, EQ
                  + "61001f57" // PUSH2 31, JUMPI
                  + "00"
                  + "5b00"
                  + "5b600160005500"),
          Map.entry("unassigned", "0c"));

  @ParameterizedTest(name = "{0} optimized={1}")
  @MethodSource("evms")
  void edgeProgramsAtEveryGasLimit(final Fork fork, final boolean optimized, final EVM evm) {
    for (final Map.Entry<String, String> program : EDGE_PROGRAMS.entrySet()) {
      final Bytes code = Bytes.fromHexString(program.getValue());
      final Outcome unlimited = run(evm, code, 10_000_000L, UNTRACED);
      assertThat(run(evm, code, 10_000_000L, TRACED)).as(program.getKey()).isEqualTo(unlimited);
      final long used = 10_000_000L - unlimited.remainingGas();
      final long step = Math.max(1, used / 400);
      for (long gas = 0; gas <= used + 1; gas += step) {
        assertThat(run(evm, code, gas, UNTRACED))
            .as("%s with %d gas", program.getKey(), gas)
            .isEqualTo(run(evm, code, gas, TRACED));
      }
    }
  }

  @ParameterizedTest(name = "{0} optimized={1}")
  @MethodSource("evms")
  void randomPrograms(final Fork fork, final boolean optimized, final EVM evm) {
    final Random random = new Random(0x5eed + fork.name().hashCode() + (optimized ? 1 : 0));
    for (int i = 0; i < 1500; i++) {
      final Bytes code = randomProgram(random);
      final long gas = random.nextInt(4) == 0 ? random.nextInt(200) : random.nextInt(60_000);
      final Outcome traced;
      final Outcome untraced;
      try {
        traced = run(evm, code, gas, TRACED);
        untraced = run(evm, code, gas, UNTRACED);
      } catch (final RuntimeException e) {
        throw new AssertionError("program " + code.toHexString() + " with " + gas + " gas", e);
      }
      assertThat(untraced).as("program %s with %d gas", code.toHexString(), gas).isEqualTo(traced);
    }
  }

  @ParameterizedTest(name = "{0} optimized={1}")
  @MethodSource("evms")
  void inlineOperationsChargeWhatTheOperationsCharge(
      final Fork fork, final boolean optimized, final EVM evm) {
    // Every opcode the untraced loop executes inline, run with plenty of gas and then on every
    // gas limit up to what it uses, so that each one is also the one that runs out.
    final String arithmetic =
        "6001600201" // ADD
            + "600503" // SUB
            + "600316" // AND
            + "601017" // OR
            + "15" // ISZERO
            + "600014" // EQ
            + "600110" // LT
            + "600111" // GT
            + "60041b" // SHL
            + "60021c" // SHR
            + "604052" // MSTORE, expanding memory
            + "604051" // MLOAD
            + "600435" // CALLDATALOAD
            + "5050"; // POP x2
    final int base = arithmetic.length() / 2;
    final String stack =
        "5b" // JUMPDEST
            + (atLeast(evm, EvmSpecVersion.SHANGHAI) ? "5f" : "58") // PUSH0 from Shanghai, else PC
            + "6001" // PUSH1
            + "610102" // PUSH2
            + "7f"
            + "11".repeat(32) // PUSH32
            + "8081" // DUP1, DUP2
            + "9091" // SWAP1, SWAP2
            + "5050505050" // POP x5
            + String.format("600160%02x57", base + 55) // JUMPI taken
            + "00"
            + String.format("5b60%02x56", base + 60) // JUMPDEST, JUMP
            + "00"
            + "5b600060ff57" // JUMPDEST, JUMPI not taken
            + "00";
    final Bytes code = Bytes.fromHexString(arithmetic + stack);
    final Outcome unlimited = run(evm, code, 1_000_000L, UNTRACED);
    if (atLeast(evm, EvmSpecVersion.CONSTANTINOPLE)) {
      assertThat(unlimited.haltReason()).isEmpty();
      assertThat(unlimited.state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    } else {
      // no shifts before Constantinople
      assertThat(unlimited.haltReason()).startsWith("INVALID_OPERATION");
    }
    assertThat(unlimited).isEqualTo(run(evm, code, 1_000_000L, TRACED));
    final long used = 1_000_000L - unlimited.remainingGas();
    for (long gas = 0; gas <= used; gas++) {
      assertThat(run(evm, code, gas, UNTRACED))
          .as("%d gas", gas)
          .isEqualTo(run(evm, code, gas, TRACED));
    }
  }

  private static Bytes randomProgram(final Random random) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final List<Integer> jumpTargets = new ArrayList<>();
    final List<Integer> jumpSites = new ArrayList<>();
    final int length = 1 + random.nextInt(120);
    for (int i = 0; i < length; i++) {
      final int kind = random.nextInt(100);
      if (kind < 25) {
        final int size =
            random.nextInt(8) == 0 ? 0 : 1 + random.nextInt(random.nextBoolean() ? 4 : 32);
        out.write(0x5f + size);
        for (int b = 0; b < size; b++) {
          out.write(random.nextInt(3) == 0 ? 0 : random.nextInt(256));
        }
      } else if (kind < 37) {
        out.write(0x80 + random.nextInt(random.nextBoolean() ? 3 : 16));
      } else if (kind < 45) {
        out.write(0x90 + random.nextInt(random.nextBoolean() ? 3 : 16));
      } else if (kind < 50) {
        out.write(0x50);
      } else if (kind < 56) {
        jumpTargets.add(out.size());
        out.write(0x5b);
      } else if (kind < 63) {
        // PUSH2 destination, patched below, then JUMP or JUMPI
        jumpSites.add(out.size());
        out.write(0x61);
        out.write(0);
        out.write(0);
        out.write(random.nextBoolean() ? 0x56 : 0x57);
      } else if (kind < 66) {
        out.write(random.nextBoolean() ? 0x56 : 0x57);
      } else if (kind < 80) {
        final int[] arithmetic = {
          0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x10, 0x11, 0x12,
          0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x51, 0x52,
          0x53, 0x58, 0x59, 0x5a, 0x35, 0x36
        };
        out.write(arithmetic[random.nextInt(arithmetic.length)]);
      } else if (kind < 84) {
        // a small operand first, so that shifts, BYTE and SIGNEXTEND see in-range arguments
        out.write(0x60);
        out.write(random.nextInt(random.nextBoolean() ? 40 : 300) & 0xff);
        final int[] small = {0x0b, 0x1a, 0x1b, 0x1c, 0x1d, 0x04, 0x06, 0x02, 0x05, 0x07};
        out.write(small[random.nextInt(small.length)]);
      } else if (kind < 89) {
        final int[] environment = {
          0x30, 0x32, 0x33, 0x34, 0x35, 0x36, 0x38, 0x3a, 0x3d, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46,
          0x47, 0x48, 0x4a, 0x4b, 0x58, 0x59, 0x5a
        };
        out.write(environment[random.nextInt(environment.length)]);
      } else if (kind < 94) {
        // memory at small offsets, so that most accesses need no expansion
        final int op = new int[] {0x51, 0x52, 0x53}[random.nextInt(3)];
        if (op != 0x51) {
          out.write(0x60);
          out.write(random.nextInt(256));
        }
        out.write(0x60);
        out.write(random.nextInt(100));
        out.write(op);
      } else if (kind < 96) {
        // DUPN, SWAPN, EXCHANGE and their immediate
        out.write(0xe6 + random.nextInt(3));
        out.write(random.nextInt(256));
      } else {
        out.write(random.nextInt(256));
      }
    }
    final byte[] code = out.toByteArray();
    for (final int site : jumpSites) {
      final int destination =
          jumpTargets.isEmpty() || random.nextInt(8) == 0
              ? random.nextInt(code.length + 2)
              : jumpTargets.get(random.nextInt(jumpTargets.size()));
      code[site + 1] = (byte) (destination >> 8);
      code[site + 2] = (byte) destination;
    }
    return Bytes.wrap(code);
  }

  private static boolean atLeast(final EVM evm, final EvmSpecVersion version) {
    return evm.getEvmVersion().ordinal() >= version.ordinal();
  }

  private static String repeat(final String hex, final int times) {
    return hex.repeat(times);
  }

  private static Outcome run(
      final EVM evm, final Bytes code, final long gas, final boolean traced) {
    final StackCapture tracer = new StackCapture(traced);
    final ToyWorld world = new ToyWorld();
    final WorldUpdater setup = world.updater();
    setup.getOrCreate(CONTRACT).setBalance(Wei.of(1_000_000));
    final MutableAccount callee = setup.getOrCreate(CALLEE);
    callee.setCode(CALLEE_CODE);
    setup.commit();
    final WorldUpdater updater = world.updater();
    final MessageFrame frame =
        new TestMessageFrameBuilder()
            .worldUpdater(updater)
            .initialGas(gas)
            .inputData(Bytes.fromHexString("0x0102030405060708090a"))
            .code(new Code(code))
            .blockValues(BLOCK_VALUES)
            .build();
    final MessageCallProcessor processor =
        new MessageCallProcessor(evm, new PrecompileContractRegistry());
    final Deque<MessageFrame> frames = frame.getMessageFrameStack();
    while (!frames.isEmpty()) {
      processor.process(frames.peekFirst(), tracer);
    }
    return new Outcome(
        frame.getState(),
        frame
            .getExceptionalHaltReason()
            .map(reason -> reason.name() + ": " + reason.getDescription())
            .orElse(""),
        frame.getRemainingGas(),
        frame.getPC(),
        tracer.stack,
        frame.shadowReadMemory(0, frame.memoryByteSize()).toHexString(),
        frame.getOutputData().toHexString(),
        frame.getReturnData().toHexString(),
        frame.getLogs(),
        frame.getGasRefund(),
        storage(updater, CONTRACT) + storage(updater, CALLEE));
  }

  private static String storage(final WorldUpdater updater, final Address address) {
    return Optional.ofNullable(updater.get(address))
        .map(account -> account.getStorageValue(UInt256.ZERO).toHexString())
        .orElse("");
  }

  private record Outcome(
      MessageFrame.State state,
      String haltReason,
      long remainingGas,
      int pc,
      List<String> stack,
      String memory,
      String output,
      String returnData,
      List<Log> logs,
      long gasRefund,
      String storage) {}
}
