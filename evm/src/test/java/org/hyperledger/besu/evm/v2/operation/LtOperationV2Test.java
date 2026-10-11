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
package org.hyperledger.besu.evm.v2.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2.getV2StackItem;

import org.hyperledger.besu.evm.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LtOperationV2Test extends BinaryOperationV2Test {

  public LtOperationV2Test() {
    super(new LtOperationV2(new FrontierGasCalculator()));
  }

  /** (a, b, lt(a, b), case); a is on top of the stack. */
  static Iterable<Arguments> data() {
    return List.of(
        Arguments.of("0x01", "0x02", "0x01", "smaller"),
        Arguments.of("0x02", "0x01", "0x00", "larger"),
        Arguments.of("0x05", "0x05", "0x00", "equal"),
        Arguments.of(
            "0x0123456789abcdeffedcba98765432100f0f0f0f0f0f0f0ff0f0f0f0f0f0f0f0",
            "0x0123456789abcdeffedcba98765432100f0f0f0f0f0f0f0ff0f0f0f0f0f0f0f1",
            "0x01",
            "differs in the least significant limb only"),
        Arguments.of(
            "0x0123456789abcdf0fedcba98765432100f0f0f0f0f0f0f0ff0f0f0f0f0f0f0f0",
            "0x0123456789abcdeffedcba98765432100f0f0f0f0f0f0f0ff0f0f0f0f0f0f0f1",
            "0x00",
            "the most significant limb decides"),
        Arguments.of(
            "0x8000000000000000000000000000000000000000000000000000000000000000",
            "0x01",
            "0x00",
            "unsigned, so the top bit makes a value large"),
        Arguments.of(
            "0x00",
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0x01",
            "zero against the largest value"));
  }

  @ParameterizedTest(name = "{index}: {3}")
  @MethodSource("data")
  void ltOperation(
      final String a, final String b, final String expected, final String description) {
    final MessageFrame frame =
        new TestMessageFrameBuilderV2()
            .pushStackItem(Bytes32.fromHexString(b))
            .pushStackItem(Bytes32.fromHexString(a))
            .build();

    final Operation.OperationResult result = operation.execute(frame, null);

    assertThat(result.getHaltReason()).isNull();
    assertThat(frame.stackTopV2()).isEqualTo(1);
    assertThat(getV2StackItem(frame, 0))
        .isEqualTo(UInt256.fromBytesBE(Bytes32.fromHexString(expected).toArrayUnsafe()));
  }

  /** LT, GT, SLT and SGT share LtOperationV2.compare, which this checks against BigInteger. */
  @Test
  void comparesLikeBigInteger() {
    final Random random = new Random(7);
    for (int i = 0; i < 2_000; i++) {
      final byte[] a = new byte[32];
      final byte[] b = new byte[32];
      random.nextBytes(a);
      random.nextBytes(b);
      // share 0 to 4 leading limbs, so that the comparison has to look further down
      System.arraycopy(a, 0, b, 0, 8 * random.nextInt(5));
      final int unsigned = new BigInteger(1, a).compareTo(new BigInteger(1, b));
      final int signed = new BigInteger(a).compareTo(new BigInteger(b));
      assertThat(compare(0x10, a, b)).isEqualTo(unsigned < 0);
      assertThat(compare(0x11, a, b)).isEqualTo(unsigned > 0);
      assertThat(compare(0x12, a, b)).isEqualTo(signed < 0);
      assertThat(compare(0x13, a, b)).isEqualTo(signed > 0);
    }
  }

  /** Runs the comparison with a on top of the stack and b below it. */
  private static boolean compare(final int opcode, final byte[] a, final byte[] b) {
    final long[] stack = new long[8];
    StackUtil.pushBytes32(Bytes32.wrap(b), stack, 0);
    StackUtil.pushBytes32(Bytes32.wrap(a), stack, 1);
    assertThat(LtOperationV2.compare(stack, 2, 4, opcode, Long.MAX_VALUE))
        .isNotEqualTo(EvmLoopInlining.FALLBACK);
    assertThat(stack[0] | stack[1] | stack[2]).isZero();
    return stack[3] == 1L;
  }

  @Test
  void gasCostIsVeryLowTier() {
    final MessageFrame frame =
        new TestMessageFrameBuilderV2()
            .pushStackItem(Bytes32.fromHexString("0x01"))
            .pushStackItem(Bytes32.fromHexString("0x02"))
            .build();

    final Operation.OperationResult result = operation.execute(frame, null);

    assertThat(result.getGasCost()).isEqualTo(new FrontierGasCalculator().getVeryLowTierGasCost());
  }
}
