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

final class Word256Comparison {

  static boolean isNegative(final Word256 a) {
    return a.l0 < 0;
  }

  static int compareUnsigned(final Word256 a, final Word256 b) {
    int cmp = Long.compareUnsigned(a.l0, b.l0);
    if (cmp != 0) return cmp;
    cmp = Long.compareUnsigned(a.l1, b.l1);
    if (cmp != 0) return cmp;
    cmp = Long.compareUnsigned(a.l2, b.l2);
    if (cmp != 0) return cmp;
    return Long.compareUnsigned(a.l3, b.l3);
  }

  static int compareSigned(final Word256 a, final Word256 b) {
    // Signed comparison: interpret the word as a signed 2's complement 256-bit integer.
    final boolean aNegative = a.isNegative();
    final boolean bNegative = b.isNegative();

    if (aNegative != bNegative) {
      return aNegative ? -1 : 1;
    }

    // Same sign: compare unsigned
    return compareUnsigned(a, b);
  }
}
