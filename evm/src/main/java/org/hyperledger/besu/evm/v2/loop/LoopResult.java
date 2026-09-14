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

import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason.DefaultExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;

import java.util.Optional;

/**
 * The result an opcode hands back to the table loop, packed into one {@code long} so that no object
 * is allocated per operation.
 *
 * <pre>
 * bits  0-10  new stack top
 * bits 11-27  next program counter
 * bits 28-57  gas to charge beyond what the loop already charged
 * bit  58     the result came from an operation on the old contract, which has already
 *             charged the frame and left its stack top and pc there
 * bits 59-63  halt code: 0 for none, otherwise an index into the default halt reasons, or
 *             {@link #CUSTOM_HALT} when the reason has been stored on the frame
 * </pre>
 */
public final class LoopResult {

  private static final int TOP_BITS = 11;
  private static final int PC_BITS = 17;
  private static final int GAS_BITS = 30;
  private static final int PC_SHIFT = TOP_BITS;
  private static final int GAS_SHIFT = PC_SHIFT + PC_BITS;
  private static final int LEGACY_SHIFT = GAS_SHIFT + GAS_BITS;
  private static final int HALT_SHIFT = LEGACY_SHIFT + 1;
  private static final long TOP_MASK = (1L << TOP_BITS) - 1;
  private static final long PC_MASK = (1L << PC_BITS) - 1;
  private static final long GAS_MASK = (1L << GAS_BITS) - 1;

  /** Halt code for a reason that is not one of the default reasons. */
  public static final int CUSTOM_HALT = 31;

  private static final DefaultExceptionalHaltReason[] HALTS = DefaultExceptionalHaltReason.values();

  private LoopResult() {}

  /**
   * A successful operation with no gas beyond the table cost.
   *
   * @param top the new stack top
   * @param pc the next program counter
   * @return the packed result
   */
  public static long ok(final int top, final int pc) {
    return top | ((long) pc << PC_SHIFT);
  }

  /**
   * A successful operation that charges gas beyond the table cost.
   *
   * @param top the new stack top
   * @param pc the next program counter
   * @param gas the gas to charge
   * @return the packed result
   */
  public static long ok(final int top, final int pc, final long gas) {
    return top | ((long) pc << PC_SHIFT) | (Math.min(gas, GAS_MASK) << GAS_SHIFT);
  }

  /**
   * The result of an operation on the old contract, after it ran.
   *
   * @param top the stack top it left on the frame
   * @param pc the program counter it left on the frame plus its increment
   * @return the packed result
   */
  public static long legacy(final int top, final int pc) {
    return top | ((long) pc << PC_SHIFT) | (1L << LEGACY_SHIFT);
  }

  /**
   * An exceptional halt.
   *
   * @param frame the frame, which keeps the reason when it is not a default one
   * @param reason the halt reason
   * @return the packed result
   */
  public static long halt(final MessageFrame frame, final ExceptionalHaltReason reason) {
    if (reason instanceof DefaultExceptionalHaltReason d
        && d != DefaultExceptionalHaltReason.NONE) {
      return (long) d.ordinal() << HALT_SHIFT;
    }
    frame.setExceptionalHaltReason(Optional.of(reason));
    return (long) CUSTOM_HALT << HALT_SHIFT;
  }

  /**
   * An exceptional halt with a default reason.
   *
   * @param reason the halt reason
   * @return the packed result
   */
  public static long halt(final DefaultExceptionalHaltReason reason) {
    return (long) reason.ordinal() << HALT_SHIFT;
  }

  /**
   * The new stack top.
   *
   * @param result the packed result
   * @return the stack top
   */
  public static int top(final long result) {
    return (int) (result & TOP_MASK);
  }

  /**
   * The next program counter.
   *
   * @param result the packed result
   * @return the program counter
   */
  public static int pc(final long result) {
    return (int) ((result >>> PC_SHIFT) & PC_MASK);
  }

  /**
   * The gas to charge beyond the table cost.
   *
   * @param result the packed result
   * @return the gas
   */
  public static long gas(final long result) {
    return (result >>> GAS_SHIFT) & GAS_MASK;
  }

  /**
   * Whether the result came from an operation on the old contract.
   *
   * @param result the packed result
   * @return true if so
   */
  public static boolean isLegacy(final long result) {
    return ((result >>> LEGACY_SHIFT) & 1L) != 0L;
  }

  /**
   * The halt code, 0 when the operation did not halt.
   *
   * @param result the packed result
   * @return the halt code
   */
  public static int haltCode(final long result) {
    return (int) (result >>> HALT_SHIFT);
  }

  /**
   * The halt reason for a code.
   *
   * @param frame the frame, consulted for a custom reason
   * @param code the halt code
   * @return the reason
   */
  public static ExceptionalHaltReason haltReason(final MessageFrame frame, final int code) {
    if (code == CUSTOM_HALT) {
      return frame.getExceptionalHaltReason().orElse(DefaultExceptionalHaltReason.NONE);
    }
    return HALTS[code];
  }
}
