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

import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.hyperledger.besu.evm.internal.Words.clampedAdd;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.word256.Word256;

import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** The Create2 operation. */
public class EOFCreateOperation extends AbstractCreateOperation {

  /** Opcode 0xEC for operation EOFCREATE */
  public static final int OPCODE = 0xec;

  private static final Bytes PREFIX = Bytes.fromHexString("0xFF");

  /**
   * Instantiates a new EOFCreate operation.
   *
   * @param gasCalculator the gas calculator
   */
  public EOFCreateOperation(final GasCalculator gasCalculator) {
    super(OPCODE, "EOFCREATE", 4, 1, gasCalculator, 1);
  }

  @Override
  public long cost(final MessageFrame frame, final Supplier<Code> codeSupplier) {
    final long inputOffset = frame.getStackItem(2).clampedToLong();
    final long inputSize = frame.getStackItem(3).clampedToLong();
    return clampedAdd(
        gasCalculator().memoryExpansionGasCost(frame, inputOffset, inputSize),
        clampedAdd(
            gasCalculator().txCreateCost(),
            gasCalculator().createKeccakCost(codeSupplier.get().getSize())));
  }

  @Override
  public Address generateTargetContractAddress(final MessageFrame frame, final Code initcode) {
    final Address sender = frame.getRecipientAddress();
    final Bytes32 salt = Bytes32.wrap(frame.getStackItem(1).toBytes());
    final Bytes32 hash = keccak256(Bytes.concatenate(PREFIX, sender, salt, initcode.getCodeHash()));
    return Address.extract(hash);
  }

  @Override
  protected Code getInitCode(final MessageFrame frame, final EVM evm) {
    final Code code = frame.getCode();
    int startIndex = frame.getPC() + 1;
    final int initContainerIndex = code.readU8(startIndex);

    return code.getSubContainer(initContainerIndex, null, evm).orElse(null);
  }

  @Override
  protected Bytes getInputData(final MessageFrame frame) {
    final long inputOffset = frame.getStackItem(2).clampedToLong();
    final long inputSize = frame.getStackItem(3).clampedToLong();
    return frame.readMemory(inputOffset, inputSize);
  }

  @Override
  protected int getPcIncrement() {
    return 2;
  }

  @Override
  protected void fail(final MessageFrame frame) {
    final long inputOffset = frame.getStackItem(2).clampedToLong();
    final long inputSize = frame.getStackItem(3).clampedToLong();
    frame.readMemory(inputOffset, inputSize);
    frame.popStackItems(getStackItemsConsumed());
    frame.pushStackItem(Word256.ZERO);
  }
}
