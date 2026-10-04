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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai;

import org.hyperledger.besu.config.GenesisConfig;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.ExecutionContextTestFixture;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Reads of state that the block accumulator already holds, through the stacked updaters of a
 * transaction, the way SLOAD, SSTORE and BALANCE reach it.
 */
@State(Scope.Thread)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StorageReadPathBenchmark {

  private static final int SLOTS = 1024;
  private static final Address CONTRACT =
      Address.fromHexString("0x00000000000000000000000000000000c0ffee01");
  private static final Address OTHER =
      Address.fromHexString("0x00000000000000000000000000000000c0ffee02");

  /** Stacked updaters above the block accumulator: two per transaction and one per call frame. */
  @Param({"3", "6", "9"})
  public int levels;

  private WorldUpdater top;
  private byte[][] keys;
  private int index;

  @Setup(Level.Trial)
  public void setUp() {
    final ExecutionContextTestFixture fixture =
        ExecutionContextTestFixture.builder(GenesisConfig.fromResource("/genesis-jmh.json"))
            .dataStorageFormat(DataStorageFormat.BONSAI)
            .build();
    final MutableWorldState worldState = fixture.getStateArchive().getWorldState();

    final WorldUpdater setup = worldState.updater();
    final MutableAccount contract = setup.createAccount(CONTRACT, 1, Wei.ZERO);
    contract.setCode(Bytes.fromHexString("0x6000"));
    final MutableAccount other = setup.createAccount(OTHER, 1, Wei.ONE);
    other.setCode(Bytes.fromHexString("0x6001"));
    keys = new byte[SLOTS][];
    for (int i = 0; i < SLOTS; i++) {
      // most slots are mapping entries, whose keys are hashes
      final Bytes32 key =
          Bytes32.wrap(Hash.hash(Bytes.ofUnsignedInt(i)).getBytes().toArrayUnsafe());
      keys[i] = key.toArray();
      contract.setStorageValue(UInt256.fromBytes(key), UInt256.valueOf(i + 1L));
    }
    setup.commit();
    worldState.persist(null);

    WorldUpdater updater = worldState.updater();
    for (int i = 0; i < levels; i++) {
      updater = updater.updater();
    }
    top = updater;
    // a called contract is tracked by the updater of its frame
    top.getAccount(CONTRACT);
    // earlier transactions of the block left the slots and the other account in the accumulator
    for (final byte[] key : keys) {
      top.get(CONTRACT).getStorageValue(UInt256.fromBytes(Bytes32.wrap(key)));
    }
    top.get(OTHER);
  }

  private UInt256 nextKey() {
    // SLOAD builds a new key from the stack for every read
    return UInt256.fromBytes(Bytes32.wrap(keys[index++ & (SLOTS - 1)]));
  }

  @Benchmark
  public UInt256 sload() {
    return top.get(CONTRACT).getStorageValue(nextKey());
  }

  @Benchmark
  public void sstoreReads(final Blackhole blackhole) {
    final MutableAccount account = top.getAccount(CONTRACT);
    final UInt256 key = nextKey();
    blackhole.consume(account.getStorageValue(key));
    blackhole.consume(account.getOriginalStorageValue(key));
  }

  @Benchmark
  public Wei balanceOfUntrackedAccount() {
    return top.get(OTHER).getBalance();
  }
}
