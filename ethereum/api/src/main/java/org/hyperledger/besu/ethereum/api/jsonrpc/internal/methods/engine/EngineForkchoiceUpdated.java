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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.INVALID;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.INVALID_TERMINAL_BLOCK;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.SYNCING;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.VALID;
import static org.hyperledger.besu.util.Slf4jLambdaHelper.debugLambda;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator.ForkchoiceResult;
import org.hyperledger.besu.consensus.merge.blockcreation.PayloadIdentifier;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.EngineForkchoiceUpdatedParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.EnginePayloadAttributesParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EngineUpdateForkchoiceResult;
import org.hyperledger.besu.ethereum.core.BlockHeader;

import java.util.Optional;

import io.vertx.core.Vertx;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineForkchoiceUpdated extends ExecutionEngineJsonRpcMethod {
  private static final Logger LOG = LoggerFactory.getLogger(EngineForkchoiceUpdated.class);
  private final MergeMiningCoordinator mergeCoordinator;

  public EngineForkchoiceUpdated(
      final Vertx vertx,
      final ProtocolContext protocolContext,
      final MergeMiningCoordinator mergeCoordinator) {
    super(vertx, protocolContext);
    this.mergeCoordinator = mergeCoordinator;
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_FORKCHOICE_UPDATED.getMethodName();
  }

  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    final Object requestId = requestContext.getRequest().getId();

    final EngineForkchoiceUpdatedParameter forkChoice =
            requestContext.getRequiredParameter(0, EngineForkchoiceUpdatedParameter.class);
    final Optional<EnginePayloadAttributesParameter> maybePayloadAttributes =
            requestContext.getOptionalParameter(1, EnginePayloadAttributesParameter.class);

    ForkchoiceResult result =
            mergeCoordinator.updateForkChoice(
                    forkChoice.getHeadBlockHash(), forkChoice.getFinalizedBlockHash(), forkChoice.getSafeBlockHash());

    if (result.getStatus() != ForkchoiceResult.Status.VALID) {
      return failedForkchoiceUpdateResult(requestId, result);
    }

    final Optional<BlockHeader> newHead = result.getNewHead();

    Optional<PayloadIdentifier> payloadId = Optional.empty();

    if (newHead.isPresent()) {
      // begin preparing a block if we have a non-empty payload attributes param
      payloadId =
              maybePayloadAttributes.map(
                      payloadAttributes ->
                              mergeCoordinator.preparePayload(
                                      newHead.get(),
                                      payloadAttributes.getTimestamp(),
                                      payloadAttributes.getPrevRandao(),
                                      payloadAttributes.getSuggestedFeeRecipient()));


    payloadId.ifPresent(
            pid ->
                    debugLambda(
                            LOG,
                            "returning identifier {} for requested payload {}",
                            pid::toHexString,
                            () -> maybePayloadAttributes.map(EnginePayloadAttributesParameter::serialize)));
  }

    return new JsonRpcSuccessResponse(
        requestId,
        new EngineUpdateForkchoiceResult(
            VALID,
            result.getNewHead().map(BlockHeader::getHash).orElse(null),
            payloadId.orElse(null),
            Optional.empty()));
  }

  private JsonRpcResponse failedForkchoiceUpdateResult(Object requestId, ForkchoiceResult result) {
    final Hash latestValidHash = result.getLatestValid().isPresent() ? result.getLatestValid().get() : null;
    JsonRpcResponse response;

    switch (result.getStatus()) {
      case SYNCING:
        response = new JsonRpcSuccessResponse(
                requestId,
                new EngineUpdateForkchoiceResult(SYNCING, null, null, result.getErrorMessage()));
      case INVALID:
        response = new JsonRpcSuccessResponse(
                requestId,
                new EngineUpdateForkchoiceResult(
                        INVALID,
                        latestValidHash,
                        null,
                        result.getErrorMessage()));
      case INVALID_TERMINAL_BLOCK:
        response = new JsonRpcSuccessResponse(
                requestId,
                new EngineUpdateForkchoiceResult(
                        INVALID_TERMINAL_BLOCK,
                        null,
                        null,
                        result.getErrorMessage()));
      case INVALID_FORKCHOICE_STATE:
        response = new JsonRpcErrorResponse(requestId, JsonRpcError.INVALID_FORKCHOICE_STATE);
      default:
	// will be thrown if a status is not handled
        throw new AssertionError(result.getStatus());
    }

    return response;
  }
}
