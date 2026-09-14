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

import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.v2.StackArithmetic;
import org.hyperledger.besu.evm.v2.loop.LoopResult;

import java.util.List;

/**
 * EVM v2 BLOBHASH operation (0x49, Cancun+).
 *
 * <p>Reads an index from the top of the stack and replaces it with the versioned hash at that index
 * in the transaction's blob versioned hashes list, or zero if the index is out of range.
 */
public class BlobHashOperationV2 extends AbstractOperationV2 {

  /** BLOBHASH opcode number */
  public static final int OPCODE = 0x49;

  /**
   * Instantiates a new BlobHash operation.
   *
   * @param gasCalculator the gas calculator
   */
  public BlobHashOperationV2(final GasCalculator gasCalculator) {
    super(OPCODE, "BLOBHASH", 1, 1, gasCalculator);
  }

  @Override
  public OperationResult execute(final MessageFrame frame, final EVM evm) {
    return staticOperation(frame, frame.stackDataV2());
  }

  /**
   * Execute BLOBHASH on the v2 long[] stack.
   *
   * @param frame the message frame
   * @param s the stack data
   * @return the operation result
   */
  public static OperationResult staticOperation(final MessageFrame frame, final long[] s) {
    if (!frame.stackHasItemsV2(1)) {
      return new OperationResult(3, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
    }
    final int top = frame.stackTopV2();
    if (frame.getVersionedHashes().isPresent()) {
      final List<VersionedHash> versionedHashes = frame.getVersionedHashes().get();
      // If index doesn't fit in a positive int, it's out of range
      if (!StackArithmetic.fitsInInt(s, top, 0)) {
        StackArithmetic.putAt(s, top, 0, 0L, 0L, 0L, 0L);
        return new OperationResult(3, null);
      }
      final int versionedHashIndex = (int) StackArithmetic.longAt(s, top, 0);
      if (versionedHashIndex >= 0 && versionedHashIndex < versionedHashes.size()) {
        final VersionedHash requested = versionedHashes.get(versionedHashIndex);
        final byte[] hashBytes = requested.getBytes().toArrayUnsafe();
        StackArithmetic.fromBytesAt(s, top, 0, hashBytes, 0, hashBytes.length);
      } else {
        StackArithmetic.putAt(s, top, 0, 0L, 0L, 0L, 0L);
      }
    } else {
      StackArithmetic.putAt(s, top, 0, 0L, 0L, 0L, 0L);
    }
    return new OperationResult(3, null);
  }

  /**
   * Executes the operation on the table loop's contract: the loop has checked the stack and charged
   * the fixed gas, and owns the program counter and the stack top.
   *
   * @param frame the frame
   * @param s the stack data
   * @param top the stack top
   * @param pc the program counter of this operation
   * @return the packed result
   */
  public static long exec(final MessageFrame frame, final long[] s, final int top, final int pc) {
    if (frame.getVersionedHashes().isPresent() && StackArithmetic.fitsInInt(s, top, 0)) {
      final List<VersionedHash> versionedHashes = frame.getVersionedHashes().get();
      final int index = (int) StackArithmetic.longAt(s, top, 0);
      if (index >= 0 && index < versionedHashes.size()) {
        final byte[] hashBytes = versionedHashes.get(index).getBytes().toArrayUnsafe();
        StackArithmetic.fromBytesAt(s, top, 0, hashBytes, 0, hashBytes.length);
        return LoopResult.ok(top, pc + 1);
      }
    }
    StackArithmetic.putAt(s, top, 0, 0L, 0L, 0L, 0L);
    return LoopResult.ok(top, pc + 1);
  }
}
