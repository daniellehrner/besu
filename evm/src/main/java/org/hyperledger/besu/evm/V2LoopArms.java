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
package org.hyperledger.besu.evm;

import static org.hyperledger.besu.evm.EVM.BASE_TIER_GAS;
import static org.hyperledger.besu.evm.EVM.HIGH_TIER_GAS;
import static org.hyperledger.besu.evm.EVM.JUMPDEST_GAS;
import static org.hyperledger.besu.evm.EVM.LONG_BE;
import static org.hyperledger.besu.evm.EVM.LOW_TIER_GAS;
import static org.hyperledger.besu.evm.EVM.MID_TIER_GAS;
import static org.hyperledger.besu.evm.EVM.VERY_LOW_TIER_GAS;
import static org.hyperledger.besu.evm.EVM.isJumpDestinationV2;

import org.hyperledger.besu.evm.frame.MessageFrame;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The operations the untraced EVM v2 loop runs inline, one method per arm of its switch. This is
 * the source of those arms: {@code ./gradlew :evm:generateEvmV2Loop} copies each method's body into
 * {@link EVM}'s loop, between the generated markers, and every build checks that the loop still
 * holds exactly that. Edit the arms here, never in EVM.java.
 *
 * <p>The loop is fast only while C2 keeps its state in registers, and a small change to one arm can
 * cost every operation a large share of its time. The generator therefore rejects an arm that
 * breaks one of these rules, each of which was measured:
 *
 * <ul>
 *   <li>No call other than the few that always inline: {@code Long} and {@code Math} intrinsics,
 *       {@code LONG_BE}, the frame's memory and input accessors and {@code isJumpDestinationV2}.
 *       One call C2 does not inline, in any arm, makes it keep all of the loop's locals in memory
 *       for the whole loop; one allocating arm took JUMPDEST from 0.45 to about 2 ns.
 *   <li>No allocation, lambda, string, exception or synchronization, for the same reason.
 *   <li>No loop, not even over the four limbs of a word. Every loop head is an on-stack replacement
 *       entry point, and which compilation a JVM then ends up with depends on where it happened to
 *       enter: the same program ran at 2.7k or 16k us from one JVM to the next.
 *   <li>No field of the EVM and no local of the loop other than the parameters listed below. A
 *       field is a load C2 hoists to the dispatch, where it runs for every operation.
 *   <li>Stack indices are built on {@code top} or {@code next}, code indices on {@code at}, never
 *       on {@code sp} or {@code pc}. The generator turns those three into indices carrying the
 *       arm's own bias. Built on {@code sp} directly, the same index appears in many arms, and C2
 *       computes all of them once, ahead of the dispatch, where they take the registers the loop's
 *       state needs.
 *   <li>An arm reports its outcome only through {@code return done(cost, step, delta)} or {@code
 *       return FALLBACK}; the loop's common tail applies the update. Written out in every arm, the
 *       same update is again one computation that C2 moves ahead of the dispatch.
 * </ul>
 *
 * <p>An arm handles only the case in which its operation succeeds. Whatever else it meets, it
 * returns {@code FALLBACK}, charging nothing, and the loop runs the operation through its
 * implementation, which owns every halt.
 *
 * <p>Where an arm runs every case in which its operation succeeds, the operation's class calls the
 * arm as well, giving it all the gas it wants since the class's callers charge the gas, so that the
 * loop and the class share one implementation. The other classes keep their own: PUSH2, JUMP and
 * JUMPI also run the operation after them, which a tracer has to see on its own, and MUL, DIV, MOD,
 * MSTORE, MLOAD, CALLDATALOAD and CALLDATASIZE run only their common cases here.
 *
 * <p>The methods take their parameters by name from the loop, and may declare only the ones they
 * use: {@code s} the stack, {@code sp} the number of items on it, {@code top} the index of the top
 * item's first limb and {@code next} that of the slot above it, {@code pc}, {@code at} the program
 * counter as a code index, {@code code}, {@code codeObject}, {@code opcode}, {@code gas} the gas
 * left, {@code frame}, and the fork flags {@code constantinople} and {@code shanghai}. The methods
 * appear in the loop in the order they appear here, which is how often mainnet executes their
 * operations: C2 inlines in that order and stops when the loop reaches its size budget.
 */
public final class V2LoopArms {

  /** The opcodes an arm runs, as a list, a range or both. */
  @Retention(RetentionPolicy.SOURCE)
  @Target(ElementType.METHOD)
  @interface Arm {
    int[] opcodes() default {};

    int first() default -1;

    int last() default -1;
  }

  /** What an arm returns when it leaves the operation to its implementation. */
  public static final long FALLBACK = -1L;

  private V2LoopArms() {}

  /**
   * What an arm returns when it has run the operation.
   *
   * @param cost the gas it charges
   * @param step how far it moves the program counter
   * @param delta how many items it adds to the stack
   * @return the three, packed
   */
  static long done(final long cost, final int step, final int delta) {
    return cost << 32 | (step & 0xffffffL) << 8 | (delta & 0xffL);
  }

  /**
   * The gas charged by an arm that ran its operation.
   *
   * @param outcome what the arm returned, other than {@link #FALLBACK}
   * @return the gas
   */
  public static long cost(final long outcome) {
    return outcome >>> 32;
  }

  /**
   * How far an arm that ran its operation moves the program counter.
   *
   * @param outcome what the arm returned, other than {@link #FALLBACK}
   * @return the distance, negative for a jump backwards
   */
  public static int step(final long outcome) {
    return (int) outcome >> 8;
  }

  /**
   * How many items an arm that ran its operation adds to the stack.
   *
   * @param outcome what the arm returned, other than {@link #FALLBACK}
   * @return the number, negative for items removed
   */
  public static int delta(final long outcome) {
    return (byte) outcome;
  }

  /**
   * PUSH1.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param next the index of the first limb of the slot above the top item
   * @param at the program counter as an index into the code
   * @param code the code
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x60)
  public static long push1(
      final long[] s,
      final int sp,
      final int next,
      final int at,
      final byte[] code,
      final long gas) {
    if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
      final int dst = next;
      final int i = at;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = i + 1 < code.length ? code[i + 1] & 0xff : 0;
      return done(VERY_LOW_TIER_GAS, 2, 1);
    }
    return FALLBACK;
  }

  /**
   * PUSH1-32, although the loop runs PUSH1 and PUSH2 through arms of their own; the immediate was
   * decoded when the code was analysed.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param next the index of the first limb of the slot above the top item
   * @param pc the program counter
   * @param opcode the opcode
   * @param codeObject the analysed code
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(first = 0x62, last = 0x7f)
  public static long push(
      final long[] s,
      final int sp,
      final int next,
      final int pc,
      final int opcode,
      final Code codeObject,
      final long gas) {
    if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
      final int dst = next;
      final int block = pc >>> 6;
      final long value =
          codeObject
              .pushValues[
              codeObject.pushBase[block]
                  + Long.bitCount(codeObject.pushBits[block] & ((1L << (pc & 63)) - 1))];
      if (opcode <= 0x67) { // PUSH3-8, the value itself
        s[dst] = 0;
        s[dst + 1] = 0;
        s[dst + 2] = 0;
        s[dst + 3] = value;
      } else { // PUSH9-32, where the wide values start
        final long[] wide = codeObject.pushWide;
        final int o = (int) value;
        s[dst] = wide[o];
        s[dst + 1] = wide[o + 1];
        s[dst + 2] = wide[o + 2];
        s[dst + 3] = wide[o + 3];
      }
      return done(VERY_LOW_TIER_GAS, opcode - 0x5e, 1);
    }
    return FALLBACK;
  }

  /**
   * DUP1-16.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param next the index of the first limb of the slot above the top item
   * @param opcode the opcode
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(first = 0x80, last = 0x8f)
  public static long dup(
      final long[] s, final int sp, final int next, final int opcode, final long gas) {
    final int depth = opcode - 0x7f;
    if (sp >= depth && (sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS) {
      final int from = next - (depth << 2);
      final int to = next;
      s[to] = s[from];
      s[to + 1] = s[from + 1];
      s[to + 2] = s[from + 2];
      s[to + 3] = s[from + 3];
      return done(VERY_LOW_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }

  /**
   * SWAP1-16.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param opcode the opcode
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(first = 0x90, last = 0x9f)
  public static long swap(
      final long[] s, final int sp, final int top, final int opcode, final long gas) {
    final int depth = opcode - 0x8f;
    if (sp > depth && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = top - (depth << 2);
      long t = s[a];
      s[a] = s[b];
      s[b] = t;
      t = s[a + 1];
      s[a + 1] = s[b + 1];
      s[b + 1] = t;
      t = s[a + 2];
      s[a + 2] = s[b + 2];
      s[b + 2] = t;
      t = s[a + 3];
      s[a + 3] = s[b + 3];
      s[b + 3] = t;
      return done(VERY_LOW_TIER_GAS, 1, 0);
    }
    return FALLBACK;
  }

  /**
   * JUMPDEST.
   *
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x5b)
  public static long jumpdest(final long gas) {
    if (gas >= JUMPDEST_GAS) {
      return done(JUMPDEST_GAS, 1, 0);
    }
    return FALLBACK;
  }

  /**
   * POP.
   *
   * @param sp the number of items on the stack
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x50)
  public static long pop(final int sp, final long gas) {
    if (sp >= 1 && gas >= BASE_TIER_GAS) {
      return done(BASE_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * PUSH2, and the PUSH2 JUMP and PUSH2 JUMPI that follow it.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param next the index of the first limb of the slot above the top item
   * @param pc the program counter
   * @param at the program counter as an index into the code
   * @param code the code
   * @param codeObject the analysed code
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x61)
  public static long push2(
      final long[] s,
      final int sp,
      final int top,
      final int next,
      final int pc,
      final int at,
      final byte[] code,
      final Code codeObject,
      final long gas) {
    final int i = at;
    if ((sp << 2) < s.length && gas >= VERY_LOW_TIER_GAS && i + 2 < code.length) {
      final int immediate = (code[i + 1] & 0xff) << 8 | (code[i + 2] & 0xff);
      final int following = i + 3 < code.length ? code[i + 3] & 0xff : 0;
      if (following == 0x56) {
        if (gas >= VERY_LOW_TIER_GAS + MID_TIER_GAS + JUMPDEST_GAS
            && isJumpDestinationV2(codeObject.jumpDestBitMask, immediate, code.length)) {
          // PUSH2 JUMP JUMPDEST, without the destination going through the stack
          return done(VERY_LOW_TIER_GAS + MID_TIER_GAS + JUMPDEST_GAS, immediate + 1 - pc, 0);
        }
      } else if (following == 0x57 && sp >= 1 && gas >= VERY_LOW_TIER_GAS + HIGH_TIER_GAS) {
        final int c = top;
        if ((s[c] | s[c + 1] | s[c + 2] | s[c + 3]) == 0) {
          // PUSH2 JUMPI, not taken
          return done(VERY_LOW_TIER_GAS + HIGH_TIER_GAS, 4, -1);
        }
        if (gas >= VERY_LOW_TIER_GAS + HIGH_TIER_GAS + JUMPDEST_GAS
            && isJumpDestinationV2(codeObject.jumpDestBitMask, immediate, code.length)) {
          // PUSH2 JUMPI JUMPDEST, taken
          return done(VERY_LOW_TIER_GAS + HIGH_TIER_GAS + JUMPDEST_GAS, immediate + 1 - pc, -1);
        }
      }
      final int dst = next;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = immediate;
      return done(VERY_LOW_TIER_GAS, 3, 1);
    }
    return FALLBACK;
  }

  /**
   * JUMPI, and the JUMPDEST it lands on.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param pc the program counter
   * @param code the code
   * @param codeObject the analysed code
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x57)
  public static long jumpi(
      final long[] s,
      final int sp,
      final int top,
      final int pc,
      final byte[] code,
      final Code codeObject,
      final long gas) {
    if (sp >= 2 && gas >= HIGH_TIER_GAS) {
      final int d = top;
      final int c = d - 4;
      if ((s[c] | s[c + 1] | s[c + 2] | s[c + 3]) == 0) {
        return done(HIGH_TIER_GAS, 1, -2);
      }
      final long destination = s[d + 3];
      if ((s[d] | s[d + 1] | s[d + 2]) == 0
          && gas >= HIGH_TIER_GAS + JUMPDEST_GAS
          && isJumpDestinationV2(codeObject.jumpDestBitMask, destination, code.length)) {
        return done(HIGH_TIER_GAS + JUMPDEST_GAS, (int) destination + 1 - pc, -2);
      }
    }
    return FALLBACK;
  }

  /**
   * JUMP, and the JUMPDEST it lands on.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param pc the program counter
   * @param code the code
   * @param codeObject the analysed code
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x56)
  public static long jump(
      final long[] s,
      final int sp,
      final int top,
      final int pc,
      final byte[] code,
      final Code codeObject,
      final long gas) {
    if (sp >= 1 && gas >= MID_TIER_GAS + JUMPDEST_GAS) {
      final int d = top;
      final long destination = s[d + 3];
      if ((s[d] | s[d + 1] | s[d + 2]) == 0
          && isJumpDestinationV2(codeObject.jumpDestBitMask, destination, code.length)) {
        return done(MID_TIER_GAS + JUMPDEST_GAS, (int) destination + 1 - pc, -1);
      }
    }
    return FALLBACK;
  }

  /**
   * ADD.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x01)
  public static long add(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      final long x0 = s[a + 3];
      final long y0 = s[b + 3];
      final long r0 = x0 + y0;
      final long c0 = ((x0 & y0) | ((x0 | y0) & ~r0)) >>> 63;
      final long x1 = s[a + 2];
      final long y1 = s[b + 2];
      final long r1 = x1 + y1 + c0;
      final long c1 = ((x1 & y1) | ((x1 | y1) & ~r1)) >>> 63;
      final long x2 = s[a + 1];
      final long y2 = s[b + 1];
      final long r2 = x2 + y2 + c1;
      final long c2 = ((x2 & y2) | ((x2 | y2) & ~r2)) >>> 63;
      s[b] = s[a] + s[b] + c2;
      s[b + 1] = r2;
      s[b + 2] = r1;
      s[b + 3] = r0;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * MSTORE within the memory already expanded.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param frame the frame
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x52)
  public static long mstore(
      final long[] s, final int sp, final int top, final MessageFrame frame, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final long location = s[a + 3];
      if ((s[a] | s[a + 1] | s[a + 2]) == 0
          && location >= 0
          && location <= frame.memoryByteSize() - 32) {
        final byte[] memory = frame.memoryArrayV2();
        final int i = (int) location;
        LONG_BE.set(memory, i, s[a - 4]);
        LONG_BE.set(memory, i + 8, s[a - 3]);
        LONG_BE.set(memory, i + 16, s[a - 2]);
        LONG_BE.set(memory, i + 24, s[a - 1]);
        return done(VERY_LOW_TIER_GAS, 1, -2);
      }
    }
    return FALLBACK;
  }

  /**
   * ISZERO.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x15)
  public static long iszero(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final long zero = (s[a] | s[a + 1] | s[a + 2] | s[a + 3]) == 0 ? 1L : 0L;
      s[a] = 0;
      s[a + 1] = 0;
      s[a + 2] = 0;
      s[a + 3] = zero;
      return done(VERY_LOW_TIER_GAS, 1, 0);
    }
    return FALLBACK;
  }

  /**
   * AND.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x16)
  public static long and(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      s[a - 4] &= s[a];
      s[a - 3] &= s[a + 1];
      s[a - 2] &= s[a + 2];
      s[a - 1] &= s[a + 3];
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * MLOAD and CALLDATALOAD, which both read a word from an array into the top item: MLOAD from
   * memory already expanded, CALLDATALOAD from the input data unless the word straddles its end.
   * They share an arm because each read inlines a chain of VarHandle methods into the loop.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param opcode the opcode
   * @param frame the frame
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = {0x51, 0x35})
  public static long loadWord(
      final long[] s,
      final int sp,
      final int top,
      final int opcode,
      final MessageFrame frame,
      final long gas) {
    if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final long location = s[a + 3];
      final boolean small = (s[a] | s[a + 1] | s[a + 2]) == 0 && location >= 0;
      final byte[] source;
      final boolean readable;
      if (opcode == 0x51) {
        source = frame.memoryArrayV2();
        readable = small && location <= frame.memoryByteSize() - 32;
      } else {
        source = frame.inputDataArrayIfPresent();
        if (source != null && (!small || location >= source.length)) {
          // past the end of the input
          s[a] = 0;
          s[a + 1] = 0;
          s[a + 2] = 0;
          s[a + 3] = 0;
          return done(VERY_LOW_TIER_GAS, 1, 0);
        }
        readable = source != null && source.length - location >= 32;
      }
      if (readable) {
        final int i = (int) location;
        s[a] = (long) LONG_BE.get(source, i);
        s[a + 1] = (long) LONG_BE.get(source, i + 8);
        s[a + 2] = (long) LONG_BE.get(source, i + 16);
        s[a + 3] = (long) LONG_BE.get(source, i + 24);
        return done(VERY_LOW_TIER_GAS, 1, 0);
      }
    }
    return FALLBACK;
  }

  /**
   * SUB.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x03)
  public static long sub(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      final long x0 = s[a + 3];
      final long y0 = s[b + 3];
      final long r0 = x0 - y0;
      final long b0 = ((~x0 & y0) | ((~x0 | y0) & r0)) >>> 63;
      final long x1 = s[a + 2];
      final long y1 = s[b + 2];
      final long r1 = x1 - y1 - b0;
      final long b1 = ((~x1 & y1) | ((~x1 | y1) & r1)) >>> 63;
      final long x2 = s[a + 1];
      final long y2 = s[b + 1];
      final long r2 = x2 - y2 - b1;
      final long b2 = ((~x2 & y2) | ((~x2 | y2) & r2)) >>> 63;
      s[b] = s[a] - s[b] - b2;
      s[b + 1] = r2;
      s[b + 2] = r1;
      s[b + 3] = r0;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * EQ.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x14)
  public static long eq(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      final long equal =
          ((s[a] ^ s[b]) | (s[a + 1] ^ s[b + 1]) | (s[a + 2] ^ s[b + 2]) | (s[a + 3] ^ s[b + 3]))
                  == 0
              ? 1L
              : 0L;
      s[b] = 0;
      s[b + 1] = 0;
      s[b + 2] = 0;
      s[b + 3] = equal;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * LT, GT, SLT and SGT.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param opcode the opcode
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(first = 0x10, last = 0x13)
  public static long compare(
      final long[] s, final int sp, final int top, final int opcode, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      // the most significant limb in which the two differ, or the least significant one
      final int i = s[a] != s[b] ? 0 : s[a + 1] != s[b + 1] ? 1 : s[a + 2] != s[b + 2] ? 2 : 3;
      long x = s[a + i];
      long y = s[b + i];
      if (i == 0 && opcode >= 0x12) {
        // signed: flipping the sign bits makes the unsigned comparison a signed one
        x ^= Long.MIN_VALUE;
        y ^= Long.MIN_VALUE;
      }
      final int comparison = Long.compareUnsigned(x, y);
      s[b] = 0;
      s[b + 1] = 0;
      s[b + 2] = 0;
      // LT and SLT are the even opcodes, GT and SGT the odd ones
      s[b + 3] = ((opcode & 1) == 0 ? comparison < 0 : comparison > 0) ? 1L : 0L;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * SHL, SHR and SAR, Constantinople onwards.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param opcode the opcode
   * @param constantinople whether Constantinople is active
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(first = 0x1b, last = 0x1d)
  public static long shift(
      final long[] s,
      final int sp,
      final int top,
      final int opcode,
      final boolean constantinople,
      final long gas) {
    if (constantinople && sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      final int v = a - 4;
      long w3 = s[v];
      long w2 = s[v + 1];
      long w1 = s[v + 2];
      long w0 = s[v + 3];
      final long fill = opcode == 0x1d ? w3 >> 63 : 0L;
      final long shift = s[a + 3];
      if ((s[a] | s[a + 1] | s[a + 2]) != 0 || shift < 0 || shift >= 256) {
        w3 = fill;
        w2 = fill;
        w1 = fill;
        w0 = fill;
      } else {
        final int bits = (int) shift & 63;
        final int limbs = (int) shift >>> 6;
        if (opcode == 0x1b) {
          // whole limbs first, towards the most significant
          w3 = limbs == 0 ? w3 : limbs == 1 ? w2 : limbs == 2 ? w1 : w0;
          w2 = limbs == 0 ? w2 : limbs == 1 ? w1 : limbs == 2 ? w0 : 0;
          w1 = limbs == 0 ? w1 : limbs == 1 ? w0 : 0;
          w0 = limbs == 0 ? w0 : 0;
          if (bits != 0) {
            w3 = w3 << bits | w2 >>> -bits;
            w2 = w2 << bits | w1 >>> -bits;
            w1 = w1 << bits | w0 >>> -bits;
            w0 <<= bits;
          }
        } else {
          // whole limbs first, towards the least significant
          w0 = limbs == 0 ? w0 : limbs == 1 ? w1 : limbs == 2 ? w2 : w3;
          w1 = limbs == 0 ? w1 : limbs == 1 ? w2 : limbs == 2 ? w3 : fill;
          w2 = limbs == 0 ? w2 : limbs == 1 ? w3 : fill;
          w3 = limbs == 0 ? w3 : fill;
          if (bits != 0) {
            w0 = w0 >>> bits | w1 << -bits;
            w1 = w1 >>> bits | w2 << -bits;
            w2 = w2 >>> bits | w3 << -bits;
            w3 = opcode == 0x1c ? w3 >>> bits : w3 >> bits;
          }
        }
      }
      s[v] = w3;
      s[v + 1] = w2;
      s[v + 2] = w1;
      s[v + 3] = w0;
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * PUSH0, Shanghai onwards.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param next the index of the first limb of the slot above the top item
   * @param shanghai whether Shanghai is active
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x5f)
  public static long push0(
      final long[] s, final int sp, final int next, final boolean shanghai, final long gas) {
    if (shanghai && (sp << 2) < s.length && gas >= BASE_TIER_GAS) {
      final int dst = next;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = 0;
      return done(BASE_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }

  /**
   * MUL when either factor fits in 64 bits.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x02)
  public static long mul(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      final int wide;
      if ((s[a] | s[a + 1] | s[a + 2]) == 0) {
        wide = b;
      } else if ((s[b] | s[b + 1] | s[b + 2]) == 0) {
        wide = a;
      } else {
        wide = -1;
      }
      if (wide >= 0) {
        final long m = s[(wide == a ? b : a) + 3];
        final long x3 = s[wide];
        final long x2 = s[wide + 1];
        final long x1 = s[wide + 2];
        final long x0 = s[wide + 3];
        final long h0 = Math.unsignedMultiplyHigh(x0, m);
        final long h1 = Math.unsignedMultiplyHigh(x1, m);
        final long h2 = Math.unsignedMultiplyHigh(x2, m);
        final long l1 = x1 * m;
        final long r1 = l1 + h0;
        // a high half is at most 2^64 - 2, so adding a carry to one cannot overflow
        final long l2 = x2 * m;
        final long r2 = l2 + h1 + (Long.compareUnsigned(r1, l1) < 0 ? 1L : 0L);
        s[b] = x3 * m + h2 + (Long.compareUnsigned(r2, l2) < 0 ? 1L : 0L);
        s[b + 1] = r2;
        s[b + 2] = r1;
        s[b + 3] = x0 * m;
        return done(LOW_TIER_GAS, 1, -1);
      }
    }
    return FALLBACK;
  }

  /**
   * CALLDATASIZE.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param next the index of the first limb of the slot above the top item
   * @param frame the frame
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x36)
  public static long calldatasize(
      final long[] s, final int sp, final int next, final MessageFrame frame, final long gas) {
    final byte[] data = frame.inputDataArrayIfPresent();
    if (data != null && (sp << 2) < s.length && gas >= BASE_TIER_GAS) {
      final int dst = next;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = data.length;
      return done(BASE_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }

  /**
   * DIV and MOD when both operands fit in 64 bits.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param opcode the opcode
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = {0x04, 0x06})
  public static long divMod(
      final long[] s, final int sp, final int top, final int opcode, final long gas) {
    if (sp >= 2 && gas >= LOW_TIER_GAS) {
      final int a = top;
      final int b = a - 4;
      if ((s[a] | s[a + 1] | s[a + 2] | s[b] | s[b + 1] | s[b + 2]) == 0) {
        final long x = s[a + 3];
        final long y = s[b + 3];
        s[b + 3] =
            y == 0 ? 0L : opcode == 0x04 ? Long.divideUnsigned(x, y) : Long.remainderUnsigned(x, y);
        return done(LOW_TIER_GAS, 1, -1);
      }
    }
    return FALLBACK;
  }

  /**
   * OR.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x17)
  public static long or(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      s[a - 4] |= s[a];
      s[a - 3] |= s[a + 1];
      s[a - 2] |= s[a + 2];
      s[a - 1] |= s[a + 3];
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * NOT.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x19)
  public static long not(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 1 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      s[a] = ~s[a];
      s[a + 1] = ~s[a + 1];
      s[a + 2] = ~s[a + 2];
      s[a + 3] = ~s[a + 3];
      return done(VERY_LOW_TIER_GAS, 1, 0);
    }
    return FALLBACK;
  }

  /**
   * GAS: what is left once GAS itself is paid.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param next the index of the first limb of the slot above the top item
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x5a)
  public static long gasLeft(final long[] s, final int sp, final int next, final long gas) {
    if ((sp << 2) < s.length && gas >= BASE_TIER_GAS) {
      final int dst = next;
      s[dst] = 0;
      s[dst + 1] = 0;
      s[dst + 2] = 0;
      s[dst + 3] = gas - BASE_TIER_GAS;
      return done(BASE_TIER_GAS, 1, 1);
    }
    return FALLBACK;
  }

  /**
   * SIGNEXTEND.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x0b)
  public static long signextend(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= LOW_TIER_GAS) {
      final int a = top;
      final int v = a - 4;
      final long index = s[a + 3];
      if ((s[a] | s[a + 1] | s[a + 2]) == 0 && index >= 0 && index < 31) {
        // the sign bit and the limb holding it, limbs counted from the least significant
        final int signBit = (int) index * 8 + 7;
        final int limb = v + 3 - (signBit >>> 6);
        final long below = -1L >>> (63 - (signBit & 63));
        final long fill = (s[limb] >>> (signBit & 63) & 1L) == 0 ? 0L : -1L;
        s[limb] = (s[limb] & below) | (fill & ~below);
        // and every limb above it
        if (limb > v) {
          s[v] = fill;
        }
        if (limb > v + 1) {
          s[v + 1] = fill;
        }
        if (limb > v + 2) {
          s[v + 2] = fill;
        }
      }
      return done(LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }

  /**
   * XOR.
   *
   * @param s the stack, four limbs per item
   * @param sp the number of items on the stack
   * @param top the index of the top item's first limb
   * @param gas the gas left
   * @return the outcome, or {@link #FALLBACK}
   */
  @Arm(opcodes = 0x18)
  public static long xor(final long[] s, final int sp, final int top, final long gas) {
    if (sp >= 2 && gas >= VERY_LOW_TIER_GAS) {
      final int a = top;
      s[a - 4] ^= s[a];
      s[a - 3] ^= s[a + 1];
      s[a - 2] ^= s[a + 2];
      s[a - 1] ^= s[a + 3];
      return done(VERY_LOW_TIER_GAS, 1, -1);
    }
    return FALLBACK;
  }
}
