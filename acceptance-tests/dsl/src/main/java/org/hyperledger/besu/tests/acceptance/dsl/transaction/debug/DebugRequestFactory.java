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
package org.hyperledger.besu.tests.acceptance.dsl.transaction.debug;

import static java.util.Collections.emptyList;

import java.util.List;

import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthBlock;

public class DebugRequestFactory {
  public record BadBlock(EthBlock.Block block, String hash, String rlp) {}

  public static class GetBadBlocksResponse extends Response<List<BadBlock>> {}

  private final Web3jService web3jService;

  public DebugRequestFactory(final Web3jService web3jService) {
    this.web3jService = web3jService;
  }

  Request<?, GetBadBlocksResponse> getBadBlocks() {
    return new Request<>(
        "debug_getBadBlocks", emptyList(), web3jService, GetBadBlocksResponse.class);
  }
}
