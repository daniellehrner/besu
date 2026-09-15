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
  void pushTablesHoldNarrowImmediatesAsValuesAndWideOnesAsLimbs() {
    // PUSH1 0xab, PUSH8 max, PUSH9 0x01 + 8 zero bytes, PUSH32 all 0x11
    final Code code =
        new Code(
            Bytes.fromHexString(
                "0x60ab"
                    + "67ffffffffffffffff"
                    + "68010000000000000000"
                    + "7f1111111111111111111111111111111111111111111111111111111111111111"));
    assertThat(Long.bitCount(code.pushBits()[0])).isEqualTo(4);
    assertThat(code.pushValues()).hasSize(4);
    assertThat(code.pushValues()[0]).isEqualTo(0xabL);
    assertThat(code.pushValues()[1]).isEqualTo(-1L);
    assertThat(code.pushValues()[2]).isEqualTo(0L);
    assertThat(code.pushValues()[3]).isEqualTo(4L);
    assertThat(code.pushWide()).hasSize(8);
    assertThat(code.pushWide()[0]).isEqualTo(0L);
    assertThat(code.pushWide()[1]).isEqualTo(0L);
    assertThat(code.pushWide()[2]).isEqualTo(0x1L);
    assertThat(code.pushWide()[3]).isEqualTo(0L);
    assertThat(code.pushWide()[4]).isEqualTo(0x1111111111111111L);
    assertThat(code.pushWide()[7]).isEqualTo(0x1111111111111111L);
  }

  @Test
  void truncatedImmediateReadsMissingBytesAsZero() {
    // PUSH3 with a single byte left in the code, and PUSH20 with none
    assertThat(new Code(Bytes.fromHexString("0x62ab")).pushValues()[0]).isEqualTo(0xab0000L);
    final Code code = new Code(Bytes.fromHexString("0x73"));
    assertThat(code.pushValues()[0]).isEqualTo(0L);
    assertThat(code.pushWide()).containsOnly(0L);
  }

  @Test
  void pushInsideAnImmediateIsNotAPush() {
    // PUSH1 0x60, PUSH1 0x01: the 0x60 byte is data
    final Code code = new Code(Bytes.fromHexString("0x60606001"));
    assertThat(Long.bitCount(code.pushBits()[0])).isEqualTo(2);
    assertThat(code.pushValues()).containsExactly(0x60L, 0x01L);
  }

  @Test
  void pushOrdinalsRankAcrossBlocks() {
    // 40 x (PUSH1 i) spans the 64-byte block boundary
    final StringBuilder hex = new StringBuilder("0x");
    for (int i = 0; i < 40; i++) {
      hex.append(String.format("60%02x", i));
    }
    final Code code = new Code(Bytes.fromHexString(hex.toString()));
    assertThat(code.pushBase()).containsExactly(0, 32);
    for (int i = 0; i < 40; i++) {
      final int pc = 2 * i;
      final int block = pc >>> 6;
      final int ordinal =
          code.pushBase()[block] + Long.bitCount(code.pushBits()[block] & ((1L << (pc & 63)) - 1));
      assertThat(code.pushValues()[ordinal]).isEqualTo(i);
    }
  }

  @Test
  void pushTablesAlsoProvideTheJumpDestMask() {
    final Code code = spy(new Code(Bytes.fromHexString("0x6003565b00")));
    code.pushValues();
    assertThat(code.isJumpDestInvalid(3)).isFalse();
    assertThat(code.isJumpDestInvalid(4)).isTrue();
    Mockito.verify(code, times(0)).calculateJumpDestBitMask();
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
