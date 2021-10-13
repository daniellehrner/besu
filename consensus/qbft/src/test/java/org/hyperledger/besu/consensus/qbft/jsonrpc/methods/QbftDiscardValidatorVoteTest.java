/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.consensus.qbft.jsonrpc.methods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.common.validator.ValidatorProvider;
import org.hyperledger.besu.consensus.common.validator.VoteProvider;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;

import java.util.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class QbftDiscardValidatorVoteTest {
  private final ValidatorProvider validatorProvider = mock(ValidatorProvider.class);
  private final VoteProvider voteProvider = mock(VoteProvider.class);
  private final String QBFT_METHOD = "qbft_discardValidatorVote";
  private final String JSON_RPC_VERSION = "2.0";
  private QbftDiscardValidatorVote method;

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setup() {
    method = new QbftDiscardValidatorVote(validatorProvider);
    when(validatorProvider.getVoteProvider()).thenReturn(Optional.of(voteProvider));
  }

  @Test
  public void returnsCorrectMethodName() {
    assertThat(method.getName()).isEqualTo(QBFT_METHOD);
  }

  @Test
  public void exceptionWhenNoParamsSupplied() {
    final JsonRpcRequestContext request = requestWithParams();

    expectedException.expect(InvalidJsonRpcParameters.class);
    expectedException.expectMessage("Missing required json rpc parameter at index 0");

    method.response(request);
  }

  @Test
  public void exceptionWhenInvalidAddressParameterSupplied() {
    final JsonRpcRequestContext request = requestWithParams("InvalidAddress");

    expectedException.expect(InvalidJsonRpcParameters.class);
    expectedException.expectMessage("Invalid json rpc parameter at index 0");

    method.response(request);
  }

  @Test
  public void methodNotEnabledWhenNoVoteProvider() {
    final JsonRpcRequestContext request = requestWithParams(Address.fromHexString("1"));
    final JsonRpcResponse expectedResponse =
        new JsonRpcErrorResponse(request.getRequest().getId(), JsonRpcError.METHOD_NOT_ENABLED);
    when(validatorProvider.getVoteProvider()).thenReturn(Optional.empty());

    final JsonRpcResponse response = method.response(request);

    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);
  }

  @Test
  public void discardValidator() {
    final Address parameterAddress = Address.fromHexString("1");
    final JsonRpcRequestContext request = requestWithParams(parameterAddress);

    final JsonRpcResponse expectedResponse =
        new JsonRpcSuccessResponse(request.getRequest().getId(), true);

    final JsonRpcResponse response = method.response(request);

    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);

    verify(voteProvider).discardVote(parameterAddress);
  }

  private JsonRpcRequestContext requestWithParams(final Object... params) {
    return new JsonRpcRequestContext(new JsonRpcRequest(JSON_RPC_VERSION, QBFT_METHOD, params));
  }
}
