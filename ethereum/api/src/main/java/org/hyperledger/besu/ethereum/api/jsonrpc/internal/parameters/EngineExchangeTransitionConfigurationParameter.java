/*
 * Copyright Hyperledger Besu Contributors.
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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Difficulty;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EngineExchangeTransitionConfigurationParameter {
  private final Difficulty terminalTotalDifficulty;
  private final Hash terminalBlockHash;
  private final long terminalBlockNumber;

  @JsonCreator
  public EngineExchangeTransitionConfigurationParameter(
      @JsonProperty("terminalTotalDifficulty") final String terminalTotalDifficulty,
      @JsonProperty("terminalBlockHash") final String terminalBlockHash,
      @JsonProperty("terminalBlockNumber") final long terminalBlockNumber) {
    this.terminalTotalDifficulty = Difficulty.fromHexString(terminalTotalDifficulty);
    this.terminalBlockHash = Hash.fromHexString(terminalBlockHash);
    this.terminalBlockNumber = terminalBlockNumber;
  }

  public Difficulty getTerminalTotalDifficulty() {
    return terminalTotalDifficulty;
  }

  public Hash getTerminalBlockHash() {
    return terminalBlockHash;
  }

  public long getTerminalBlockNumber() {
    return terminalBlockNumber;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    EngineExchangeTransitionConfigurationParameter that =
        (EngineExchangeTransitionConfigurationParameter) o;
    return terminalTotalDifficulty.equals(that.terminalTotalDifficulty)
        && terminalBlockHash.equals(that.terminalBlockHash)
        && terminalBlockNumber == that.terminalBlockNumber;
  }

  @Override
  public int hashCode() {
    return Objects.hash(terminalTotalDifficulty, terminalBlockHash, terminalBlockNumber);
  }
}
