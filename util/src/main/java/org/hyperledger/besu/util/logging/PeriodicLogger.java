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
package org.hyperledger.besu.util.logging;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class PeriodicLogger implements Handler<Long> {
    private static final int TIMER_INTERVAL = 250;

    private final Vertx vertx;
    private final long timerId;
    private final List<PeriodicLoggerMessage> messages;

    public PeriodicLogger() {
        vertx = Vertx.vertx();
        timerId = vertx.setPeriodic(TIMER_INTERVAL, this);
        messages = new ArrayList<>();
    }

    public void addInfoLogMessage(final Logger logger, final Function<Void, String> messageGenerator, final long interval) {
        messages.add(new PeriodicLoggerMessage(logger, PeriodicLoggerMessage.Level.INFO, LocalDateTime.now(), messageGenerator, interval))
    }

    @Override
    public void handle(Long event) {

        logger.log(Level.DEBUG, "");
    }

    public void stop() {
        vertx.cancelTimer(timerId);
    }
}
