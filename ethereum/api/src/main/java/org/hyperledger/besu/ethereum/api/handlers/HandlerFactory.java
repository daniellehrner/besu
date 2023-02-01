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
package org.hyperledger.besu.ethereum.api.handlers;

import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration;
import org.hyperledger.besu.ethereum.api.jsonrpc.authentication.AuthenticationService;
import org.hyperledger.besu.ethereum.api.jsonrpc.execution.JsonRpcExecutor;
import org.hyperledger.besu.ethereum.api.jsonrpc.execution.JsonRpcExecutorVerticle;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.opentelemetry.api.trace.Tracer;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

public class HandlerFactory {

  public static Handler<RoutingContext> timeout(
      final TimeoutOptions globalOptions, final Map<String, JsonRpcMethod> methods) {
    assert methods != null && globalOptions != null;
    return TimeoutHandler.handler(
        Optional.of(globalOptions),
        methods.keySet().stream().collect(Collectors.toMap(String::new, ignored -> globalOptions)));
  }

  public static Handler<RoutingContext> authentication(
      final AuthenticationService authenticationService, final Collection<String> noAuthRpcApis) {
    return AuthenticationHandler.handler(authenticationService, noAuthRpcApis);
  }

  public static Handler<RoutingContext> jsonRpcParser() {
    return JsonRpcParserHandler.handler();
  }

  public static Handler<RoutingContext> jsonRpcExecutor(
      final JsonRpcExecutor jsonRpcExecutor,
      final Tracer tracer,
      final JsonRpcConfiguration jsonRpcConfiguration) {
    return BlockingJsonRpcExecutorHandler.handler(jsonRpcExecutor, tracer, jsonRpcConfiguration);
  }

  public static Handler<RoutingContext> jsonRpcExecutor(
      final Vertx vertx,
      final List<JsonRpcExecutorVerticle> jsonRpcExecutorVerticles,
      final JsonRpcExecutor blockingJsonRpcExecutor,
      final Tracer tracer) {
    return new NonBlockingJsonRpcExecutorHandler(
        vertx, jsonRpcExecutorVerticles, blockingJsonRpcExecutor, tracer);
  }
}
