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
package org.hyperledger.besu.evm.operation;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

import org.apache.tuweni.bytes.Bytes32;

/** The Call operation. */
public class ExtCallOperation extends AbstractExtCallOperation {

  /** The constant OPCODE. */
  public static final int OPCODE = 0xF8;

  static final int STACK_VALUE = 3;

  /**
   * Instantiates a new Call operation.
   *
   * @param gasCalculator the gas calculator
   */
  public ExtCallOperation(final GasCalculator gasCalculator) {
    super(OPCODE, "EXTCALL", 4, 1, gasCalculator);
  }

  @Override
  protected Wei value(final MessageFrame frame) {
    return Wei.wrap(Bytes32.wrap(frame.getStackItem(STACK_VALUE).toBytes()));
  }

  @Override
  protected Wei apparentValue(final MessageFrame frame) {
    return value(frame);
  }

  @Override
  protected long inputDataOffset(final MessageFrame frame) {
    return frame.getStackItem(STACK_INPUT_OFFSET).clampedToLong();
  }

  @Override
  protected long inputDataLength(final MessageFrame frame) {
    return frame.getStackItem(STACK_INPUT_LENGTH).clampedToLong();
  }

  @Override
  protected Address address(final MessageFrame frame) {
    return to(frame);
  }

  @Override
  protected Address sender(final MessageFrame frame) {
    return frame.getRecipientAddress();
  }
}
