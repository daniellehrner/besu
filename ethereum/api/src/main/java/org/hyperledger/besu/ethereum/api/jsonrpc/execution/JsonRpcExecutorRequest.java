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
package org.hyperledger.besu.ethereum.api.jsonrpc.execution;

import java.util.Optional;
import java.util.function.Supplier;

import io.opentelemetry.context.Context;
import io.vertx.ext.auth.User;

public class JsonRpcExecutorRequest {
  private final Optional<User> optionalUser;
  private final Context spanContext;
  private final Supplier<Boolean> alive;

  protected JsonRpcExecutorRequest(
      final Optional<User> optionalUser, final Context spanContext, final Supplier<Boolean> alive) {
    this.optionalUser = optionalUser;
    this.spanContext = spanContext;
    this.alive = alive;
  }

  public Optional<User> getOptionalUser() {
    return optionalUser;
  }

  public Context getSpanContext() {
    return spanContext;
  }

  public Supplier<Boolean> getAlive() {
    return alive;
  }
}