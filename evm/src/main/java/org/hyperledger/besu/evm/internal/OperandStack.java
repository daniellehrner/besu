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

  /** Instantiates a new Operand stack. */
  public OperandStack(final int maxStackSize) {
    this.entries = new Word256[maxStackSize];
    this.maxStackSize = maxStackSize;
  }

  public void push(final Word256 value) {
    if (++top >= maxStackSize) {
      throw new OverflowException();
    }
    entries[top] = value;
  }

  public Word256 pop() {
    if (top < 0) {
      throw new UnderflowException();
    }
    final Word256 val = entries[top];
    entries[top--] = null;
    return val;
  }

  public Word256 peek() {
    return top >= 0 ? entries[top] : null;
  }

  public Word256 get(final int offset) {
    final int index = top - offset;

    if (index < 0) {
      throw new UnderflowException();
    }

    return entries[index];
  }

  public void set(final int offset, final Word256 value) {
    final int index = top - offset;

    if (index < 0 || index > top) {
      throw new UnderflowException();
    }

    entries[index] = value;
  }

  public int size() {
    return top + 1;
  }

  public void bulkPop(final int n) {
    if (n > size()) {
      throw new UnderflowException();
    }

    Arrays.fill(entries, top - n + 1, top + 1, null);
    top -= n;
  }

  public boolean isFull() {
    return top + 1 >= maxStackSize;
  }

  public boolean isEmpty() {
    return top < 0;
  }
}
