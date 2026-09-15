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
package org.hyperledger.besu.evm.v2.op;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.internal.EvmConfiguration.EvmV2Loop;
import org.hyperledger.besu.evm.operation.InvalidOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.v2.loop.LoopTables;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OpTableTest {

  private static final EvmConfiguration CONFIG =
      new EvmConfiguration(
          EvmConfiguration.DEFAULT.jumpDestCacheWeightKB(),
          EvmConfiguration.DEFAULT.worldUpdaterMode(),
          true,
          true,
          EvmV2Loop.VTABLE);

  static Stream<Function<EvmConfiguration, EVM>> forks() {
    return Stream.of(
        MainnetEVMs::frontier,
        MainnetEVMs::byzantium,
        MainnetEVMs::istanbul,
        MainnetEVMs::london,
        MainnetEVMs::shanghai,
        MainnetEVMs::cancun,
        MainnetEVMs::prague,
        MainnetEVMs::amsterdam);
  }

  @ParameterizedTest
  @MethodSource("forks")
  void everyOpcodeHasAnOperationThatMatchesTheTables(final Function<EvmConfiguration, EVM> fork) {
    final EVM evm = fork.apply(CONFIG);
    final OpTable table = new OpTable(evm, evm.getOperations());
    final LoopTables tables = new LoopTables(evm.getOperations(), evm.getGasCalculator());
    final Operation[] registered = evm.getOperations().getOperations();
    final List<Integer> wrapped = new ArrayList<>();
    for (int opcode = 0; opcode < 256; opcode++) {
      final Op op = table.get(opcode);
      assertThat(op).as("opcode %x", opcode).isNotNull();
      assertThat(op.opcode).isEqualTo(opcode);
      assertThat(op.stackIn).isEqualTo(tables.stackIn[opcode]);
      assertThat(op.stackOut).isEqualTo(tables.stackOut[opcode]);
      final boolean missing =
          registered[opcode] == null || registered[opcode] instanceof InvalidOperation;
      assertThat(op instanceof ControlOps.Invalid).as("opcode %x", opcode).isEqualTo(missing);
      if (op instanceof LegacyOps.Registry) {
        wrapped.add(opcode);
      }
    }
    // every operation of the mainnet forks has its own object; the registry wrapper is a safety
    // net for operations this table does not know
    assertThat(wrapped).as("opcodes run through the registry").isEmpty();
  }
}
