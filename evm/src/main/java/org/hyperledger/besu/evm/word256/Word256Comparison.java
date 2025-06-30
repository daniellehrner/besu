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
package org.hyperledger.besu.evm.word256;

/**
 * Utility class for comparing two {@link Word256} instances.
 *
 * <p>This class provides methods to compare Word256 values both in signed and unsigned order.
 */
final class Word256Comparison {

  /**
   * Checks if the given Word256 is negative.
   *
   * @param a the Word256 to check
   * @return true if the Word256 is negative, false otherwise
   */
  static boolean isNegative(final Word256 a) {
    // MSB is now l3
    return a.l3 < 0;
  }

  /**
   * Compares two Word256 instances in unsigned order.
   *
   * @param a the first Word256
   * @param b the second Word256
   * @return a negative integer, zero, or a positive integer as the first argument is less than,
   *     equal to, or greater than the second.
   */
  static int compareUnsigned(final Word256 a, final Word256 b) {
    // Compare most to least significant
    int cmp = Long.compareUnsigned(a.l3, b.l3);
    if (cmp != 0) return cmp;
    cmp = Long.compareUnsigned(a.l2, b.l2);
    if (cmp != 0) return cmp;
    cmp = Long.compareUnsigned(a.l1, b.l1);
    if (cmp != 0) return cmp;
    return Long.compareUnsigned(a.l0, b.l0);
  }

  /**
   * Compares two Word256 instances in signed order.
   *
   * @param a the first Word256
   * @param b the second Word256
   * @return a negative integer, zero, or a positive integer as the first argument is less than,
   *     equal to, or greater than the second.
   */
  static int compareSigned(final Word256 a, final Word256 b) {
    final boolean aNegative = isNegative(a);
    final boolean bNegative = isNegative(b);

    if (aNegative != bNegative) {
      return aNegative ? -1 : 1;
    }

    return compareUnsigned(a, b);
  }

  /**
   * Checks if a Word256 value is zero.
   *
   * @param w the Word256 value to check
   * @return true if w is zero, false otherwise
   */
  static boolean isZero(final Word256 w) {
    return w.l0 == 0 && w.l1 == 0 && w.l2 == 0 && w.l3 == 0;
  }
}
