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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.code;

import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.function.Function;

/**
 * A storage that can serve a stored code value where it lies, instead of copying it out first.
 *
 * <p>Code is read to be turned into a {@link org.hyperledger.besu.evm.Code}, which needs the code
 * bytes in one array and the jump destination analysis in one {@code long[]}. Handing the value
 * over as bytes first makes that two copies of the code instead of one, which for the code sizes an
 * attacker can reach is the larger part of the read.
 */
public interface MappedCodeStorage {

  /**
   * Reads the value stored for a code hash.
   *
   * <p>The segment is only readable while {@code reader} runs, so the reader has to copy out of it
   * whatever it returns.
   *
   * @param codeHash the code hash the value is stored under
   * @param reader turns the stored value into the result
   * @param <T> what the reader builds from the value
   * @return the reader's result, or empty if nothing is stored under the hash
   */
  <T> Optional<T> readCode(byte[] codeHash, Function<MemorySegment, T> reader);
}
