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
package org.hyperledger.besu.evm.processor;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.util.Deque;

/**
 * Runs a message frame stack to completion.
 *
 * <p>This is the outer of the two EVM loops: it owns the message frame stack and decides which
 * frame runs next, while {@link org.hyperledger.besu.evm.EVM#runToHalt} is the inner loop that
 * executes the code of one frame. Nested calls never recurse on the Java stack.
 */
public final class MessageFrameRunner {

  private final MessageCallProcessor messageCallProcessor;
  private final ContractCreationProcessor contractCreationProcessor;

  /**
   * Instantiates a new message frame runner.
   *
   * @param messageCallProcessor the processor for {@link MessageFrame.Type#MESSAGE_CALL} frames
   * @param contractCreationProcessor the processor for {@link MessageFrame.Type#CONTRACT_CREATION}
   *     frames
   */
  public MessageFrameRunner(
      final MessageCallProcessor messageCallProcessor,
      final ContractCreationProcessor contractCreationProcessor) {
    this.messageCallProcessor = messageCallProcessor;
    this.contractCreationProcessor = contractCreationProcessor;
  }

  /**
   * Runs the initial frame, and every frame it spawns, until the message frame stack is empty.
   *
   * @param initialFrame the frame at the bottom of the message frame stack
   * @param operationTracer the operation tracer
   */
  public void run(final MessageFrame initialFrame, final OperationTracer operationTracer) {
    final Deque<MessageFrame> messageFrameStack = initialFrame.getMessageFrameStack();
    MessageFrame frame = messageFrameStack.peekFirst();
    while (frame != null) {
      final AbstractMessageProcessor processor =
          switch (frame.getType()) {
            case MESSAGE_CALL -> messageCallProcessor;
            case CONTRACT_CREATION -> contractCreationProcessor;
          };
      processor.process(frame, operationTracer);
      // either the child that was just spawned, or the parent that just received a result
      frame = messageFrameStack.peekFirst();
    }
  }
}
