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
package org.hyperledger.besu.ethereum.vm.operations;

import static org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider.createInMemoryWorldStateArchive;
import static org.mockito.Mockito.mock;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.MessageFrameTestFixture;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.CancunGasCalculator;
import org.hyperledger.besu.evm.operation.TLoadOperation;
import org.hyperledger.besu.evm.operation.TStoreOperation;

import java.util.concurrent.TimeUnit;

import org.apache.tuweni.units.bigints.UInt256;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures how TSTORE, TLOAD and the rollback of a failed transaction scale with the number of
 * distinct transient storage slots. A single-slot benchmark cannot show this: a cheap TSTORE loop
 * with a fresh key per iteration fills ~146k slots within the 2^24 transaction gas limit.
 */
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class TransientStorageScalingBenchmark {

  private static final UInt256 VALUE = UInt256.MAX_VALUE;

  @State(Scope.Thread)
  public static class Slots {
    @Param({"1000", "10000", "150000"})
    int slotCount;

    TStoreOperation tstore;
    TLoadOperation tload;
    MessageFrame frame;
    UInt256[] keys;

    @Setup(Level.Trial)
    public void setUp() {
      final CancunGasCalculator gasCalculator = new CancunGasCalculator();
      tstore = new TStoreOperation(gasCalculator);
      tload = new TLoadOperation(gasCalculator);
      frame =
          new MessageFrameTestFixture()
              .address(Address.fromHexString("0x18675309"))
              .worldUpdater(createInMemoryWorldStateArchive().getWorldState().updater())
              .blockHeader(new BlockHeaderTestFixture().buildHeader())
              .blockchain(mock(Blockchain.class))
              .build();
      keys = new UInt256[slotCount];
      for (int i = 0; i < slotCount; i++) {
        keys[i] = UInt256.valueOf(i);
      }
    }

    void storeAll() {
      for (final UInt256 key : keys) {
        frame.pushStackItem(VALUE);
        frame.pushStackItem(key);
        tstore.execute(frame, null);
      }
    }
  }

  /** Slots that start empty for every invocation. */
  @State(Scope.Thread)
  public static class EmptySlots extends Slots {
    @TearDown(Level.Invocation)
    public void rollback() {
      frame.rollback();
    }
  }

  /** Slots that are filled once and only read by the benchmark. */
  @State(Scope.Thread)
  public static class FilledSlots extends Slots {
    @Override
    @Setup(Level.Trial)
    public void setUp() {
      super.setUp();
      storeAll();
      final UInt256 lastKey = keys[slotCount - 1];
      if (!VALUE.equals(frame.getTransientStorageValue(frame.getRecipientAddress(), lastKey))) {
        throw new IllegalStateException("TSTORE did not populate the transient storage");
      }
    }
  }

  /**
   * TSTORE into {@code slotCount} distinct slots.
   *
   * @param slots the benchmark state
   */
  @Benchmark
  public void tstoreDistinctSlots(final EmptySlots slots) {
    slots.storeAll();
  }

  /**
   * TSTORE into {@code slotCount} distinct slots, then roll them back as a failed transaction does.
   *
   * @param slots the benchmark state
   */
  @Benchmark
  public void tstoreDistinctSlotsAndRollback(final EmptySlots slots) {
    slots.storeAll();
    slots.frame.rollback();
  }

  /**
   * TLOAD each of {@code slotCount} populated slots.
   *
   * @param slots the benchmark state
   * @param blackhole consumes the loaded values
   */
  @Benchmark
  public void tloadDistinctSlots(final FilledSlots slots, final Blackhole blackhole) {
    final MessageFrame frame = slots.frame;
    for (final UInt256 key : slots.keys) {
      frame.pushStackItem(key);
      slots.tload.execute(frame, null);
      blackhole.consume(frame.popStackItem());
    }
  }
}
