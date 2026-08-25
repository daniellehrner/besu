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
package org.hyperledger.besu.ethereum.api.jsonrpc;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import io.vertx.core.streams.WriteStream;

/**
 * Blocks the calling thread until a Vertx {@link WriteStream} write queue is no longer full. This
 * prevents unbounded accumulation of Netty direct-memory buffers when the producer is faster than
 * the network can drain.
 *
 * <p>Safe to call from {@code executeBlocking} worker threads only — must never be called from the
 * Vertx event loop.
 */
public final class StreamBackpressure {

  private static final long DRAIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);

  /**
   * How often the wait is broken to re-evaluate the abort condition. A drain still wakes the waiter
   * immediately via the drain handler, so this only bounds how quickly a dead peer is noticed.
   */
  private static final long ABORT_POLL_MILLIS = 50;

  private StreamBackpressure() {}

  /**
   * If the write queue is full, blocks until it drains below the low watermark, the peer goes away,
   * or the timeout expires.
   *
   * @param stream the Vertx WriteStream to check
   * @param aborted returns true once the response can no longer be written — the connection was
   *     closed, the response was already ended (for example by the JSON-RPC timeout handler), or a
   *     previous write failed. Re-evaluated every 50 ms while waiting.
   * @throws ClosedChannelException if {@code aborted} becomes true while waiting
   * @throws IOException if the timeout expires or the thread is interrupted
   */
  public static void awaitDrain(final WriteStream<?> stream, final BooleanSupplier aborted)
      throws IOException {
    awaitDrain(stream, aborted, DRAIN_TIMEOUT_MILLIS);
  }

  static void awaitDrain(
      final WriteStream<?> stream, final BooleanSupplier aborted, final long timeoutMillis)
      throws IOException {
    if (!stream.writeQueueFull()) {
      return;
    }

    final CountDownLatch latch = new CountDownLatch(1);
    stream.drainHandler(v -> latch.countDown());
    try {
      // Re-check after setting the handler to avoid a race where the queue drained
      // between the full-check and the handler registration
      if (!stream.writeQueueFull()) {
        return;
      }
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      while (true) {
        // Checked before every wait slice: nothing counts the latch down when the peer
        // disappears, so without this the thread would park for the full timeout even though
        // the response can never be delivered.
        if (aborted.getAsBoolean()) {
          throw new ClosedChannelException();
        }
        final long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
          throw new IOException("Timed out waiting for write queue to drain");
        }
        final long waitMillis =
            Math.min(ABORT_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
        if (latch.await(waitMillis, TimeUnit.MILLISECONDS)) {
          return;
        }
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted waiting for write queue to drain", e);
    } finally {
      // Do not leave a handler pointing at a latch nobody is waiting on any more.
      stream.drainHandler(null);
    }
  }
}
