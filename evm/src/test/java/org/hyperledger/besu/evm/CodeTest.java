/*
 * Copyright contributors to Hyperledger Besu.
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
import static org.hyperledger.besu.evm.frame.MessageFrame.Type.MESSAGE_CALL;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.JumpOperation;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Random;

import jakarta.validation.constraints.NotNull;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CodeTest {

  private static final int CURRENT_PC = 1;
  private EVM evm;

  @BeforeEach
  void startUp() {
    evm = MainnetEVMs.futureEips(EvmConfiguration.DEFAULT);
  }

  @Test
  void shouldReuseJumpDestMap() {
    final JumpOperation operation = new JumpOperation(evm.getGasCalculator());
    final Bytes jumpBytes = Bytes.fromHexString("0x6003565b00");
    final Code getsCached = spy(new Code(jumpBytes));
    MessageFrame frame = createJumpFrame(getsCached);

    OperationResult result = operation.execute(frame, evm);
    assertNull(result.getHaltReason());
    Mockito.verify(getsCached, times(1)).calculateJumpDestBitMask();

    // do it again to prove we don't recalculate, and we hit the cache

    frame = createJumpFrame(getsCached);

    result = operation.execute(frame, evm);
    assertNull(result.getHaltReason());
    Mockito.verify(getsCached, times(1)).calculateJumpDestBitMask();
  }

  @Test
  void jumpDestInPushDataIsInvalid() {
    // PUSH1 0x5b: the 0x5b byte is immediate data at offset 1, not a jump destination
    final Code code = new Code(Bytes.fromHexString("0x605b5b"));

    assertThat(code.isJumpDestInvalid(1)).isTrue();
    assertThat(code.isJumpDestInvalid(2)).isFalse();
  }

  @Test
  void jumpDestInWidePushDataIsInvalid() {
    // PUSH32 of 32 JUMPDEST bytes, then a real JUMPDEST
    final Code code = new Code(Bytes.fromHexString("0x7f" + "5b".repeat(32) + "5b"));

    for (int offset = 1; offset <= 32; offset++) {
      assertThat(code.isJumpDestInvalid(offset)).isTrue();
    }
    assertThat(code.isJumpDestInvalid(33)).isFalse();
  }

  @Test
  void nonJumpDestOpcodeIsInvalid() {
    final Code code = new Code(Bytes.fromHexString("0x6003565b00"));

    assertThat(code.isJumpDestInvalid(0)).isTrue();
    assertThat(code.isJumpDestInvalid(3)).isFalse();
    assertThat(code.isJumpDestInvalid(4)).isTrue();
  }

  @Test
  void outOfRangeJumpDestIsInvalid() {
    final Code code = new Code(Bytes.fromHexString("0x5b"));

    assertThat(code.isJumpDestInvalid(-1)).isTrue();
    assertThat(code.isJumpDestInvalid(1)).isTrue();
    assertThat(code.isJumpDestInvalid(0)).isFalse();
  }

  @Test
  void truncatedPushAtEndOfCodeDoesNotOverflow() {
    // PUSH32 with no immediate data following it
    final Code code = new Code(Bytes.fromHexString("0x5b7f"));

    assertThat(code.isJumpDestInvalid(0)).isFalse();
    assertThat(code.isJumpDestInvalid(1)).isTrue();
  }

  @Test
  void emptyCodeHasNoJumpDestinations() {
    assertThat(Code.EMPTY_CODE.isJumpDestInvalid(0)).isTrue();
  }

  @Test
  void matchesNaiveAnalysisOnJumpDestRuns() {
    // Runs of JUMPDEST are marked in blocks, so cover lengths that straddle both the block and
    // the 64 byte window, with and without immediate data interrupting the run
    for (int runLength = 1; runLength <= 200; runLength++) {
      for (final String prefix : new String[] {"", "60ff", "7f" + "5b".repeat(32), "5b5b60"}) {
        final Bytes code = Bytes.fromHexString("0x" + prefix + "5b".repeat(runLength));
        assertCodeMatchesNaiveAnalysis(code);
      }
    }
  }

  private static void assertCodeMatchesNaiveAnalysis(final Bytes bytes) {
    final byte[] raw = bytes.toArrayUnsafe();
    final Code code = new Code(bytes);
    final boolean[] isImmediateData = naiveImmediateData(raw);

    for (int offset = 0; offset < raw.length; offset++) {
      final boolean expected = raw[offset] != 0x5b || isImmediateData[offset];
      assertThat(code.isJumpDestInvalid(offset))
          .describedAs("offset %d of %s", offset, bytes)
          .isEqualTo(expected);
    }
  }

  @Test
  void matchesNaiveAnalysisOnRandomCode() {
    final Random random = new Random(0xC0DE);

    for (int trial = 0; trial < 400; trial++) {
      final byte[] raw = new byte[1 + random.nextInt(2000)];
      // Odd trials are PUSH-dense, even trials are JUMPDEST runs with a rare PUSH or other opcode,
      // so that both frequent and rare word boundaries at PUSH are exercised
      final int pushOneIn = (trial & 1) == 0 ? 40 : 3;
      for (int i = 0; i < raw.length; i++) {
        raw[i] =
            switch (random.nextInt(pushOneIn)) {
              case 0 -> (byte) (0x60 + random.nextInt(32));
              case 1 -> (byte) random.nextInt(256);
              default -> (byte) 0x5b;
            };
      }

      final Code code = new Code(Bytes.wrap(raw));
      final boolean[] isImmediateData = naiveImmediateData(raw);

      for (int offset = 0; offset < raw.length; offset++) {
        final boolean expected = raw[offset] != 0x5b || isImmediateData[offset];
        assertThat(code.isJumpDestInvalid(offset))
            .describedAs("trial %d offset %d of %s", trial, offset, Bytes.wrap(raw))
            .isEqualTo(expected);
      }
    }
  }

  private static boolean[] naiveImmediateData(final byte[] raw) {
    final boolean[] isImmediateData = new boolean[raw.length];
    for (int pc = 0; pc < raw.length; ) {
      final int opcode = raw[pc] & 0xff;
      pc++;
      if (opcode >= 0x60 && opcode <= 0x7f) {
        final int size = opcode - 0x60 + 1;
        for (int i = 0; i < size && pc + i < raw.length; i++) {
          isImmediateData[pc + i] = true;
        }
        pc += size;
      }
    }
    return isImmediateData;
  }

  @NotNull
  private MessageFrame createJumpFrame(final Code getsCached) {
    final MessageFrame frame =
        MessageFrame.builder()
            .type(MESSAGE_CALL)
            .worldUpdater(mock(WorldUpdater.class))
            .initialGas(10_000L)
            .address(Address.ZERO)
            .originator(Address.ZERO)
            .contract(Address.ZERO)
            .gasPrice(Wei.ZERO)
            .inputData(Bytes.EMPTY)
            .sender(Address.ZERO)
            .value(Wei.ZERO)
            .apparentValue(Wei.ZERO)
            .code(getsCached)
            .blockValues(mock(BlockValues.class))
            .completer(f -> {})
            .miningBeneficiary(Address.ZERO)
            .blockHashLookup((__, ___) -> Hash.EMPTY)
            .build();

    frame.setPC(CURRENT_PC);
    frame.pushStackItem(UInt256.fromHexString("0x03"));
    return frame;
  }
}
