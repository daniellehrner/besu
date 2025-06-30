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
package org.hyperledger.besu.evm.internal;

import org.hyperledger.besu.evm.word256.Word256;

import java.util.Arrays;

/** The Operand stack. */
public class OperandStack {
  private final Word256[] entries;
  private final int maxStackSize;

  private int top = -1;

  /**
   * Instantiates a new Operand stack.
   *
   * @param maxStackSize the maximum size of the stack
   */
  public OperandStack(final int maxStackSize) {
    this.entries = new Word256[maxStackSize];
    this.maxStackSize = maxStackSize;
  }

  /**
   * Pushes a value onto the stack.
   *
   * @param value the value to push onto the stack
   * @throws OverflowException if the stack is full
   */
  public void push(final Word256 value) {
    if (++top >= maxStackSize) {
      throw new OverflowException();
    }
    entries[top] = value;
  }

  /**
   * Pops the top value from the stack.
   *
   * @return the top value of the stack
   * @throws UnderflowException if the stack is empty
   */
  public Word256 pop() {
    if (top < 0) {
      throw new UnderflowException();
    }
    final Word256 val = entries[top];
    entries[top--] = null;
    return val;
  }

  /**
   * Peeks at the top value of the stack without removing it.
   *
   * @return the top value of the stack, or null if the stack is empty
   */
  public Word256 peek() {
    return top >= 0 ? entries[top] : null;
  }

  /**
   * Gets the value at the specified offset from the top of the stack.
   *
   * @param offset the offset from the top of the stack (0 is the topmost item)
   * @return the value at the specified offset
   * @throws UnderflowException if the offset is out of bounds
   */
  public Word256 get(final int offset) {
    final int index = top - offset;

    if (index < 0) {
      throw new UnderflowException();
    }

    return entries[index];
  }

  /**
   * Sets the value at the specified offset in the stack.
   *
   * @param offset the offset from the top of the stack (0 is the topmost item)
   * @param value the value to set
   * @throws UnderflowException if the offset is out of bounds
   */
  public void set(final int offset, final Word256 value) {
    final int index = top - offset;

    if (index < 0 || index > top) {
      throw new UnderflowException();
    }

    entries[index] = value;
  }

  /**
   * Gets the size of the stack.
   *
   * @return the size of the stack
   */
  public int size() {
    return top + 1;
  }

  /**
   * Pops multiple items from the stack.
   *
   * @param n the number of items to pop
   * @throws UnderflowException if there are not enough items on the stack
   */
  public void bulkPop(final int n) {
    if (n > size()) {
      throw new UnderflowException();
    }

    Arrays.fill(entries, top - n + 1, top + 1, null);
    top -= n;
  }

  /**
   * Checks if the stack is full.
   *
   * @return true if the stack is full, false otherwise
   */
  public boolean isFull() {
    return top + 1 >= maxStackSize;
  }

  /**
   * Checks if the stack is empty.
   *
   * @return true if the stack is empty, false otherwise
   */
  public boolean isEmpty() {
    return top < 0;
  }
}
