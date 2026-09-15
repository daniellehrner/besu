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
package org.hyperledger.besu.evm.v2.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.internal.EvmConfiguration.EvmV2Loop;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Runs the same programs through the switch loop and each other loop and compares the frames. */
class TableLoopTest {

  private static EVM evm(final EvmV2Loop loop) {
    return MainnetEVMs.prague(
        new EvmConfiguration(
            EvmConfiguration.DEFAULT.jumpDestCacheWeightKB(),
            EvmConfiguration.DEFAULT.worldUpdaterMode(),
            true,
            true,
            loop));
  }

  /** A tracer that observes every operation, so the loops take their tracing path. */
  private static final OperationTracer COUNTING_TRACER =
      new OperationTracer() {
        @Override
        public boolean isEnabled() {
          return true;
        }
      };

  private static final EVM SWITCH = evm(EvmV2Loop.SWITCH);
  private static final Map<EvmV2Loop, EVM> EVMS = new EnumMap<>(EvmV2Loop.class);

  private static EVM evmFor(final EvmV2Loop loop) {
    return EVMS.computeIfAbsent(loop, TableLoopTest::evm);
  }

  /** Everything observable about a frame after the loop returned. */
  private record Outcome(
      String state,
      String halt,
      long gas,
      int pc,
      List<Long> stack,
      Bytes memory,
      Bytes output,
      String exception) {}

  private static Outcome run(final EVM evm, final Bytes code, final long gas) {
    return run(evm, code, Bytes.EMPTY, gas, OperationTracer.NO_TRACING);
  }

  private static Outcome run(
      final EVM evm,
      final Bytes code,
      final Bytes input,
      final long gas,
      final OperationTracer tracer) {
    final MessageFrame frame =
        new TestMessageFrameBuilderV2()
            .code(new Code(code))
            .inputData(input)
            .initialGas(gas)
            .build();
    // the message processor puts a frame into this state before it runs the interpreter
    frame.setState(MessageFrame.State.CODE_EXECUTING);
    try {
      evm.runToHalt(frame, tracer);
    } catch (final RuntimeException e) {
      // an exception escaping the interpreter is a harness artefact (the toy world has no block
      // values, for one); the loops differ only in how much frame state they had written back
      return new Outcome(
          "", "", 0L, 0, List.of(), Bytes.EMPTY, Bytes.EMPTY, e.getClass().getSimpleName());
    }
    if (frame.getState() == MessageFrame.State.EXCEPTIONAL_HALT) {
      // the frame is discarded and its gas consumed after an exceptional halt, so only the
      // reason remains observable; the switch loop leaves the pc after a PUSH's immediate when
      // the PUSH runs out of gas, where the table loop and the standard loop leave it on the
      // PUSH itself
      return new Outcome(
          frame.getState().name(),
          frame.getExceptionalHaltReason().map(Object::toString).orElse(""),
          0L,
          0,
          List.of(),
          Bytes.EMPTY,
          Bytes.EMPTY,
          null);
    }
    final List<Long> stack = new ArrayList<>();
    for (int i = 0; i < frame.stackTopV2() * 4; i++) {
      stack.add(frame.stackDataV2()[i]);
    }
    return new Outcome(
        frame.getState().name(),
        frame.getExceptionalHaltReason().map(Object::toString).orElse(""),
        frame.getRemainingGas(),
        frame.getPC(),
        stack,
        frame.readMemory(0, frame.memoryByteSize()),
        frame.getOutputData(),
        null);
  }

  private static void assertSameOutcome(final EvmV2Loop loop, final Bytes code, final long gas) {
    assertThat(run(evmFor(loop), code, gas))
        .as("%s code %s gas %d", loop, code, gas)
        .isEqualTo(run(SWITCH, code, gas));
  }

  private static final String[] PROGRAMS = {
    // arithmetic and comparison mix, then STOP
    "6001600201600302600404600505600606600707600880600809601080601180601280601380601480158016801780188019801a801b801c801d00",
    // dup, swap, pop, push0
    "6001600260038182839091929350505f5f5000",
    // counter loop: PUSH1 0 JUMPDEST PUSH1 1 ADD DUP1 PUSH1 10 GT PUSH1 2 JUMPI STOP
    "60005b6001018060 0a11600257 00".replace(" ", ""),
    // invalid jump destination
    "600556",
    // conditional jump not taken then taken
    "6000600b57 60ff 6001600b57 5b 00".replace(" ", ""),
    // stack underflow
    "01",
    // memory: MSTORE, MLOAD, MSTORE8, KECCAK256, MSIZE, MCOPY
    "7f0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20 6000 52 6000 51 60ff 6020 53 6021 6000 20 59 6010 6000 6040 5e 00"
        .replace(" ", ""),
    // RETURN and REVERT with data
    "60aa600052 6020 6000 f3".replace(" ", ""),
    "60bb600052 6020 6000 fd".replace(" ", ""),
    // INVALID and unknown opcodes
    "fe",
    "0c",
    "21",
    // PUSH truncated by end of code
    "7f0102",
    "60",
    // environment and block ops
    "30 32 33 34 36 38 3a 3d 41 42 43 44 45 46 48 4a 58 5a 00".replace(" ", ""),
    // call data
    "6000 35 6000 6000 6000 37 6000 6000 6000 39 00".replace(" ", ""),
    // a CALL suspends the frame
    "6000 6000 6000 6000 6000 6001 61ffff f1 00".replace(" ", ""),
    // SLOAD and SSTORE on the contract account, and transient storage
    "6001 6002 55 6002 54 6003 6004 5d 6004 5c 00".replace(" ", ""),
    // execution runs past the end of code
    "6001",
    // empty code
    "",
  };

  @ParameterizedTest
  @EnumSource(
      value = EvmV2Loop.class,
      names = {"TABLE", "VTABLE"})
  void programsActuallyRun(final EvmV2Loop loop) {
    final Outcome outcome = run(evmFor(loop), Bytes.fromHexString("0x600160020100"), 1_000_000L);
    assertThat(outcome.state()).isEqualTo("CODE_SUCCESS");
    assertThat(outcome.stack()).containsExactly(0L, 0L, 0L, 3L);
    assertThat(outcome.gas()).isEqualTo(1_000_000L - 9L);
  }

  @ParameterizedTest
  @EnumSource(
      value = EvmV2Loop.class,
      names = {"TABLE", "VTABLE"})
  void fixedProgramsAgree(final EvmV2Loop loop) {
    for (final String program : PROGRAMS) {
      final Bytes code = Bytes.fromHexString("0x" + program);
      assertSameOutcome(loop, code, 1_000_000L);
      assertSameOutcome(loop, code, 20L);
      assertSameOutcome(loop, code, 0L);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = EvmV2Loop.class,
      names = {"TABLE", "VTABLE"})
  void memoryFixtureAgreesWithAndWithoutTracer(final EvmV2Loop loop) {
    final Bytes code =
        Bytes.fromHexString(
            "0x6000602435146100115760005061001f565b600a61202052610100612040525b6001602435146100315760005061003f565b600061202052610100612040525b60026024351461005157600050610062565b600a60000361202052610100612040525b60036024351461007457600050610083565b61100061202052610100612040525b600a60243514610095576000506100a2565b600a612020526000612040525b600b602435146100b4576000506100c1565b6000612020526000612040525b600c602435146100d3576000506100e3565b600a600003612020526000612040525b600d602435146100f557600050610103565b611000612020526000612040525b60146024351461011557600050610125565b600561202052600a600003612040525b60156024351461013757600050610147565b600561202052637fffffff612040525b60166024351461015957600050610169565b6005612020526380000000612040525b60176024351461017b5760005061018b565b60056120205263ffffffff612040525b60186024351461019d576000506101ae565b600561202052640100000000612040525b6019602435146101c0576000506101d4565b600561202052677fffffffffffffff612040525b601a602435146101e6576000506101fa565b600561202052678000000000000000612040525b601b6024351461020c57600050610220565b60056120205267ffffffffffffffff612040525b601c6024351461023257600050610247565b60056120205268010000000000000000612040525b602060043514610258576000610262565b6120205161204051205b5060376004351461027557600050610281565b61202051600061204051375b6039600435146102935760005061029f565b61202051600061204051395b603c600435146102b1576000506102c0565b6120205160006120405161c0de3c5b603e600435146102d2576000506102de565b612020516000612040513e5b60a0600435146102f0576000506102fa565b6120205161204051a05b60a16004351461030c57600050610318565b60016120205161204051a15b60a26004351461032a57600050610338565b600260016120205161204051a25b60a36004351461034a5760005061035a565b6003600260016120205161204051a35b60a46004351461036c5760005061037e565b60046003600260016120205161204051a45b60f06004351461038f57600061039b565b61202051612040516000f05b5060f1600435146103ad5760006103c3565b600060006120205161204051600061c0de611000f15b506101f1600435146103d65760006103ec565b612020516120405160006000600061c0de611000f15b5060f2600435146103fe576000610414565b600060006120205161204051600061c0de611000f25b506101f26004351461042757600061043d565b612020516120405160006000600061c0de611000f25b5060f46004351461044f576000610464565b60006000612020516120405161c0de62100000f45b506101f46004351461047757600061048c565b61202051612040516000600061c0de62100000f45b5060f56004351461049e5760006104ad565b615a1761202051612040516000f55b5060fa600435146104bf5760006104d4565b60006000612020516120405161c0de62100000fa5b506101fa600435146104e75760006104fc565b61202051612040516000600061c0de62100000fa5b5061013e6004351461051057600050610530565b61010061010060006000600061c0de611000f150612020516000612040513e5b60f360043514610541576000610557565b6000600060406120206000630f30c0de62100000f15b5060ff6004351461056a57600050610585565b6000600060406120206000630ff0c0de62100000f1503d6000555b60006101005500");
    final Bytes input =
        Bytes.fromHexString(
            "0x1a8451e600000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000");
    assertThat(run(evmFor(loop), code, input, 80_000_000L, OperationTracer.NO_TRACING))
        .isEqualTo(run(SWITCH, code, input, 80_000_000L, OperationTracer.NO_TRACING));
    assertThat(run(evmFor(loop), code, input, 80_000_000L, COUNTING_TRACER))
        .isEqualTo(run(SWITCH, code, input, 80_000_000L, COUNTING_TRACER));
  }

  @ParameterizedTest
  @EnumSource(
      value = EvmV2Loop.class,
      names = {"TABLE", "VTABLE"})
  void stackOverflowAgrees(final EvmV2Loop loop) {
    final byte[] code = new byte[1100];
    Arrays.fill(code, (byte) 0x5f);
    assertSameOutcome(loop, Bytes.wrap(code), 1_000_000L);
    final byte[] dups = new byte[1100];
    dups[0] = 0x5f;
    Arrays.fill(dups, 1, dups.length, (byte) 0x80);
    assertSameOutcome(loop, Bytes.wrap(dups), 1_000_000L);
  }

  /** Opcodes that need no world state beyond the frame's own account. */
  private static final int[] ALPHABET = {
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x10, 0x11, 0x12,
    0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x20, 0x30, 0x32,
    0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x3d, 0x3e, 0x41, 0x42, 0x43, 0x44, 0x45,
    0x46, 0x47, 0x48, 0x4a, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a,
    0x5b, 0x5c, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x63, 0x6f, 0x73, 0x7f, 0x80, 0x81, 0x82, 0x8f,
    0x90, 0x91, 0x9f, 0xa0, 0xa1, 0xf3, 0xfd, 0xfe
  };

  @ParameterizedTest
  @EnumSource(
      value = EvmV2Loop.class,
      names = {"TABLE", "VTABLE"})
  void randomProgramsAgree(final EvmV2Loop loop) {
    final Random random = new Random(20260914L);
    for (int i = 0; i < 3000; i++) {
      final byte[] code = new byte[1 + random.nextInt(80)];
      for (int j = 0; j < code.length; j++) {
        // half the bytes are small immediates so that jumps and offsets land somewhere useful
        code[j] =
            random.nextBoolean()
                ? (byte) ALPHABET[random.nextInt(ALPHABET.length)]
                : (byte) random.nextInt(24);
      }
      final Bytes program = Bytes.wrap(code);
      assertSameOutcome(loop, program, 100_000L);
      assertSameOutcome(loop, program, 30L);
    }
  }
}
