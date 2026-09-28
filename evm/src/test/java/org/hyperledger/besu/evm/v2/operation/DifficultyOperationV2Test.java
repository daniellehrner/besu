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
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.v2.testutils.TestMessageFrameBuilderV2;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class DifficultyOperationV2Test extends NullaryOperationV2Test {

  private final GasCalculator gasCalculator = new FrontierGasCalculator();

  public DifficultyOperationV2Test() {
    super(new DifficultyOperationV2(new FrontierGasCalculator()));
  }

  @Test
  void shouldPushDifficultyToStack() {
    final MessageFrame frame = createFrame(Bytes.fromHexString("0x0123456789abcdef0102"));
    final OperationResult result = operation.execute(frame, null);
    assertThat(result.getHaltReason()).isNull();
    assertThat(getV2StackItem(frame, 0))
        .isEqualTo(new UInt256(0L, 0L, 0x0123L, 0x456789abcdef0102L));
  }

  @Test
  void shouldPushZeroWhenDifficultyIsNull() {
    final MessageFrame frame = createFrame(null);
    final OperationResult result = operation.execute(frame, null);
    assertThat(result.getHaltReason()).isNull();
    assertThat(getV2StackItem(frame, 0)).isEqualTo(UInt256.ZERO);
  }

  @Test
  void shouldReturnCorrectGasCost() {
    final MessageFrame frame = createFrame(Bytes.of(1));
    final OperationResult result = operation.execute(frame, null);
    assertThat(result.getGasCost()).isEqualTo(gasCalculator.getBaseTierGasCost());
  }

  private MessageFrame createFrame(final Bytes difficulty) {
    return new TestMessageFrameBuilderV2()
        .blockValues(
            new BlockValues() {
              @Override
              public Bytes getDifficultyBytes() {
                return difficulty;
              }
            })
        .build();
  }
}
