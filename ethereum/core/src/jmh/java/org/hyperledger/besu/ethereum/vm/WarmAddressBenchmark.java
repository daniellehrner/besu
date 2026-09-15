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
package org.hyperledger.besu.ethereum.vm;

import org.hyperledger.besu.collections.undo.UndoSet;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.internal.WarmAddressSet;

import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * One transaction's worth of EIP-2929 warm-address bookkeeping: a fresh set, a mix of first and
 * repeat touches of a few dozen accounts, and one revert in the middle. Reports nanoseconds per
 * touch, including the set's creation amortised over the transaction.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class WarmAddressBenchmark {

  private static final int TOUCHES = 400;
  private static final int REVERT_AT = 150;
  private static final int REVERT_LENGTH = 50;

  /** Warm-address set implementations under test. */
  public enum Implementation {
    TREE_SET,
    OPEN_SET
  }

  /** Minimal view of a warm-address set. */
  interface WarmAddresses {
    boolean warmUp(Address address);

    long mark();

    void undo(long mark);
  }

  @Param({"TREE_SET", "OPEN_SET"})
  private Implementation implementation;

  @Param({"8", "64"})
  private int distinctAddresses;

  private Address[] touches;

  @Setup(Level.Trial)
  public void setUp() {
    final Random random = new Random(42);
    final Address[] distinct = new Address[distinctAddresses];
    for (int i = 0; i < distinctAddresses; i++) {
      distinct[i] = Address.wrap(Bytes.random(20, random));
    }
    touches = new Address[TOUCHES];
    for (int i = 0; i < TOUCHES; i++) {
      touches[i] = distinct[random.nextInt(distinctAddresses)];
    }
  }

  @Benchmark
  @OperationsPerInvocation(TOUCHES)
  public void transaction(final Blackhole blackhole) {
    final WarmAddresses set = create(implementation);
    long mark = 0L;
    for (int i = 0; i < TOUCHES; i++) {
      if (i == REVERT_AT) {
        mark = set.mark();
      }
      if (i == REVERT_AT + REVERT_LENGTH) {
        set.undo(mark);
      }
      blackhole.consume(set.warmUp(touches[i]));
    }
  }

  private static WarmAddresses create(final Implementation implementation) {
    return switch (implementation) {
      case TREE_SET -> {
        final UndoSet<Address> set = UndoSet.of(new TreeSet<>());
        yield new WarmAddresses() {
          @Override
          public boolean warmUp(final Address address) {
            return !set.add(address);
          }

          @Override
          public long mark() {
            return set.mark();
          }

          @Override
          public void undo(final long mark) {
            set.undo(mark);
          }
        };
      }
      case OPEN_SET -> {
        final WarmAddressSet set = new WarmAddressSet();
        yield new WarmAddresses() {
          @Override
          public boolean warmUp(final Address address) {
            return set.warmUp(address);
          }

          @Override
          public long mark() {
            return set.mark();
          }

          @Override
          public void undo(final long mark) {
            set.undo(mark);
          }
        };
      }
    };
  }
}
