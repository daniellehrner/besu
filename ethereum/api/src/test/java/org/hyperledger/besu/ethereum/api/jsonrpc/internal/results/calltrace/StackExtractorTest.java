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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.calltrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.evm.tracing.TraceFrame;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StackExtractorTest {

  @Test
  @DisplayName(
      "extractCreateInitCode falls back to memory using correct stack offsets for soft-failed CREATE2")
  void extractCreateInitCode_create2_softFailure_readsMemoryAtOffsetSize() {
    // CREATE2 stack layout (top-first): value, offset, size, salt.
    // The TraceFrame snapshot stores stack bottom -> top, so the array is:
    //   [salt, size, offset, value]
    final Bytes[] stack =
        new Bytes[] {
          Bytes32.ZERO, // salt (deepest)
          Bytes.of(8), // size = 8
          Bytes.of(4), // offset = 4
          Bytes32.ZERO // value (top)
        };

    // Memory word 0: 4 leading zero bytes, then 8 known bytes, then 20 zero bytes (32B total).
    final Bytes[] memory = {
      Bytes.fromHexString(
          "0x00000000deadbeefcafebabe0000000000000000000000000000000000000000")
    };

    final TraceFrame frame =
        TraceFrame.builder()
            .setOpcode("CREATE2")
            .setStack(Optional.of(stack))
            .setMemory(Optional.of(memory))
            .setMaybeMemorySlice(Optional.empty())
            .setMaybeCode(Optional.empty())
            .build();

    final Bytes initCode = StackExtractor.extractCreateInitCode(frame, "CREATE2", false);

    assertThat(initCode).isEqualTo(Bytes.fromHexString("0xdeadbeefcafebabe"));
  }

  @Test
  @DisplayName("extractCreateInitCode handles CREATE soft failure with the same stack offsets")
  void extractCreateInitCode_create_softFailure_readsMemoryAtOffsetSize() {
    // CREATE stack layout (top-first): value, offset, size.
    // Snapshot bottom -> top: [size, offset, value]
    final Bytes[] stack =
        new Bytes[] {
          Bytes.of(4), // size = 4
          Bytes.of(2), // offset = 2
          Bytes32.ZERO // value (top)
        };

    final Bytes[] memory = {
      Bytes.fromHexString(
          "0x0000feedface0000000000000000000000000000000000000000000000000000")
    };

    final TraceFrame frame =
        TraceFrame.builder()
            .setOpcode("CREATE")
            .setStack(Optional.of(stack))
            .setMemory(Optional.of(memory))
            .setMaybeMemorySlice(Optional.empty())
            .setMaybeCode(Optional.empty())
            .build();

    final Bytes initCode = StackExtractor.extractCreateInitCode(frame, "CREATE", false);

    assertThat(initCode).isEqualTo(Bytes.fromHexString("0xfeedface"));
  }

  @Test
  @DisplayName("extractCreateInitCode prefers maybeMemorySlice when present")
  void extractCreateInitCode_prefersPreExtractedSlice() {
    // Provide a stack that, if used, would extract zeros from memory; the slice should win.
    final Bytes[] stack =
        new Bytes[] {Bytes32.ZERO, Bytes.of(8), Bytes.of(0), Bytes32.ZERO};
    final Bytes[] memory = new Bytes[] {Bytes32.ZERO};
    final Bytes preExtracted = Bytes.fromHexString("0x0123456789abcdef");

    final TraceFrame frame =
        TraceFrame.builder()
            .setOpcode("CREATE2")
            .setStack(Optional.of(stack))
            .setMemory(Optional.of(memory))
            .setMaybeMemorySlice(Optional.of(preExtracted))
            .setMaybeCode(Optional.empty())
            .build();

    final Bytes initCode = StackExtractor.extractCreateInitCode(frame, "CREATE2", false);

    assertThat(initCode).isEqualTo(preExtracted);
  }
}
