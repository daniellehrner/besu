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
package org.hyperledger.besu.evm.v2.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.gascalculator.AmsterdamGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.Operation;

import org.junit.jupiter.api.Test;

class LoopTablesTest {

  @Test
  void stackEffectsMatchTheRegisteredOperations() {
    final EVM evm = MainnetEVMs.amsterdam(EvmConfiguration.DEFAULT);
    final LoopTables tables = new LoopTables(evm.getOperations(), new AmsterdamGasCalculator());
    for (int opcode = 0; opcode < 256; opcode++) {
      final Operation op = evm.getOperationsUnsafe()[opcode];
      if (op == null || op.getStackItemsConsumed() < 0) {
        continue;
      }
      if (opcode == 0xe6 || opcode == 0xe7 || opcode == 0xe8) {
        // the depth comes from an immediate; the operations check the stack themselves
        continue;
      }
      assertThat(tables.stackIn[opcode])
          .as("stack in of %s", op.getName())
          .isEqualTo((byte) op.getStackItemsConsumed());
      assertThat(tables.stackOut[opcode])
          .as("stack out of %s", op.getName())
          .isEqualTo((byte) op.getStackItemsProduced());
    }
  }
}
