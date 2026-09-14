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

import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.operation.InvalidOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;

/**
 * Per-opcode metadata the table loop consults before dispatching: how many stack items an operation
 * reads, how many it leaves in their place, its fixed gas when the loop charges it, and whether the
 * loop or the operation owns the frame state around it.
 */
public final class LoopTables {

  /** The loop checks stack and gas from the tables and the case returns a packed result. */
  static final int NATIVE = 1;

  /** Items each opcode reads or pops. */
  public final byte[] stackIn = new byte[256];

  /** Items each opcode leaves in place of the ones it read. */
  public final byte[] stackOut = new byte[256];

  final long[] fixedGas = new long[256];
  final byte[] flags = new byte[256];

  /**
   * Builds the tables for one fork.
   *
   * @param operations the operations the fork has
   * @param gasCalculator the fork's gas calculator, for the fixed costs the loop charges
   */
  public LoopTables(final OperationRegistry operations, final GasCalculator gasCalculator) {
    // stack effects are fixed by the instruction set, not the fork: an opcode a fork lacks halts
    // in the switch before its entry matters
    inOut(0x00, 0, 0); // STOP
    for (int op = 0x01; op <= 0x0b; op++) {
      inOut(op, op == 0x08 || op == 0x09 ? 3 : 2, 1); // ADD..SIGNEXTEND, ADDMOD/MULMOD take 3
    }
    for (int op = 0x10; op <= 0x1d; op++) {
      inOut(op, op == 0x15 || op == 0x19 ? 1 : 2, 1); // LT..SAR, ISZERO/NOT take 1
    }
    inOut(0x1e, 1, 1); // CLZ
    inOut(0x20, 2, 1); // KECCAK256
    inOut(0x30, 0, 1); // ADDRESS
    inOut(0x31, 1, 1); // BALANCE
    inOut(0x32, 0, 1); // ORIGIN
    inOut(0x33, 0, 1); // CALLER
    inOut(0x34, 0, 1); // CALLVALUE
    inOut(0x35, 1, 1); // CALLDATALOAD
    inOut(0x36, 0, 1); // CALLDATASIZE
    inOut(0x37, 3, 0); // CALLDATACOPY
    inOut(0x38, 0, 1); // CODESIZE
    inOut(0x39, 3, 0); // CODECOPY
    inOut(0x3a, 0, 1); // GASPRICE
    inOut(0x3b, 1, 1); // EXTCODESIZE
    inOut(0x3c, 4, 0); // EXTCODECOPY
    inOut(0x3d, 0, 1); // RETURNDATASIZE
    inOut(0x3e, 3, 0); // RETURNDATACOPY
    inOut(0x3f, 1, 1); // EXTCODEHASH
    inOut(0x40, 1, 1); // BLOCKHASH
    for (int op = 0x41; op <= 0x48; op++) {
      inOut(op, 0, 1); // COINBASE..BASEFEE
    }
    inOut(0x49, 1, 1); // BLOBHASH
    inOut(0x4a, 0, 1); // BLOBBASEFEE
    inOut(0x4b, 0, 1); // SLOTNUM
    inOut(0x50, 1, 0); // POP
    inOut(0x51, 1, 1); // MLOAD
    inOut(0x52, 2, 0); // MSTORE
    inOut(0x53, 2, 0); // MSTORE8
    inOut(0x54, 1, 1); // SLOAD
    inOut(0x55, 2, 0); // SSTORE
    inOut(0x56, 1, 0); // JUMP
    inOut(0x57, 2, 0); // JUMPI
    inOut(0x58, 0, 1); // PC
    inOut(0x59, 0, 1); // MSIZE
    inOut(0x5a, 0, 1); // GAS
    inOut(0x5b, 0, 0); // JUMPDEST
    inOut(0x5c, 1, 1); // TLOAD
    inOut(0x5d, 2, 0); // TSTORE
    inOut(0x5e, 3, 0); // MCOPY
    for (int op = 0x5f; op <= 0x7f; op++) {
      inOut(op, 0, 1); // PUSH0..PUSH32
    }
    for (int n = 1; n <= 16; n++) {
      inOut(0x7f + n, n, n + 1); // DUPn
      inOut(0x8f + n, n + 1, n + 1); // SWAPn
    }
    for (int n = 0; n <= 4; n++) {
      inOut(0xa0 + n, 2 + n, 0); // LOGn
    }
    // DUPN, SWAPN and EXCHANGE read their depth from an immediate and check the stack themselves
    inOut(0xf0, 3, 1); // CREATE
    inOut(0xf1, 7, 1); // CALL
    inOut(0xf2, 7, 1); // CALLCODE
    inOut(0xf3, 2, 0); // RETURN
    inOut(0xf4, 6, 1); // DELEGATECALL
    inOut(0xf5, 4, 1); // CREATE2
    inOut(0xfa, 6, 1); // STATICCALL
    inOut(0xfc, 2, 1); // PAY
    inOut(0xfd, 2, 0); // REVERT
    inOut(0xff, 1, 0); // SELFDESTRUCT

    // operations already on the native contract
    final long veryLow = gasCalculator.getVeryLowTierGasCost();
    final long low = gasCalculator.getLowTierGasCost();
    final long mid = gasCalculator.getMidTierGasCost();
    native0(0x01, veryLow); // ADD
    native0(0x02, low); // MUL
    native0(0x03, veryLow); // SUB
    native0(0x04, low); // DIV
    native0(0x05, low); // SDIV
    native0(0x06, low); // MOD
    native0(0x07, low); // SMOD
    native0(0x08, mid); // ADDMOD
    native0(0x09, mid); // MULMOD
    native0(0x0a, 0L); // EXP charges its own gas
    native0(0x0b, low); // SIGNEXTEND
    for (int opcode = 0x10; opcode <= 0x1d; opcode++) {
      native0(opcode, veryLow); // LT..SAR
    }
    native0(0x1e, low); // CLZ
    final long base = gasCalculator.getBaseTierGasCost();
    native0(0x20, 0L); // KECCAK256 charges its own gas
    native0(0x30, base); // ADDRESS
    native0(0x32, base); // ORIGIN
    native0(0x33, base); // CALLER
    native0(0x34, base); // CALLVALUE
    native0(0x35, veryLow); // CALLDATALOAD
    native0(0x36, base); // CALLDATASIZE
    native0(0x37, 0L); // CALLDATACOPY
    native0(0x38, base); // CODESIZE
    native0(0x39, 0L); // CODECOPY
    native0(0x3a, base); // GASPRICE
    native0(0x3d, base); // RETURNDATASIZE
    native0(0x3e, 0L); // RETURNDATACOPY
    for (int opcode = 0x41; opcode <= 0x46; opcode++) {
      native0(opcode, base); // COINBASE..CHAINID
    }
    native0(0x47, low); // SELFBALANCE
    native0(0x48, base); // BASEFEE
    native0(0x49, veryLow); // BLOBHASH
    native0(0x4a, base); // BLOBBASEFEE
    native0(0x4b, base); // SLOTNUM
    native0(0x51, 0L); // MLOAD
    native0(0x52, 0L); // MSTORE
    native0(0x53, 0L); // MSTORE8
    native0(0x56, mid); // JUMP
    native0(0x57, gasCalculator.getHighTierGasCost()); // JUMPI
    native0(0x58, base); // PC
    native0(0x59, base); // MSIZE
    native0(0x5a, base); // GAS
    native0(0x5c, gasCalculator.getWarmStorageReadCost()); // TLOAD
    native0(0x5d, 0L); // TSTORE charges its own gas after its static check
    native0(0x5e, 0L); // MCOPY
    for (int opcode = 0x60; opcode <= 0x7f; opcode++) {
      native0(opcode, veryLow); // PUSH1-32
    }
    native0(0x50, gasCalculator.getBaseTierGasCost()); // POP
    native0(0x5b, gasCalculator.getJumpDestOperationGasCost()); // JUMPDEST
    native0(0x5f, gasCalculator.getBaseTierGasCost()); // PUSH0
    for (int opcode = 0x80; opcode <= 0x9f; opcode++) { // DUP1-16, SWAP1-16
      native0(opcode, gasCalculator.getVeryLowTierGasCost());
    }

    // an opcode the fork does not have halts as an invalid operation whatever the stack holds,
    // so the loop must not report a stack fault for it first
    final Operation[] ops = operations.getOperations();
    for (int opcode = 0; opcode < 256; opcode++) {
      if (ops[opcode] == null || ops[opcode] instanceof InvalidOperation) {
        inOut(opcode, 0, 0);
        flags[opcode] = 0;
        fixedGas[opcode] = 0L;
      }
    }
  }

  private void inOut(final int opcode, final int in, final int out) {
    stackIn[opcode] = (byte) in;
    stackOut[opcode] = (byte) out;
  }

  private void native0(final int opcode, final long gas) {
    flags[opcode] = NATIVE;
    fixedGas[opcode] = gas;
  }
}
