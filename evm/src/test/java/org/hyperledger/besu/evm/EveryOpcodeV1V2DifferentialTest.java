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
package org.hyperledger.besu.evm;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.Eip7928AccessList;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.AmsterdamGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.AbstractMessageProcessor;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.toy.ToyWorld;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Runs every opcode on the standard interpreter and on EVM v2, for each fork, and requires both to
 * end in the same state. v2 decides in its own dispatch from which fork an operation exists, so
 * this keeps that decision in line with the operations each fork registers.
 */
class EveryOpcodeV1V2DifferentialTest {

  private static final Address CONTRACT = Address.fromHexString("0xc0de");
  private static final Address CALLEE = Address.fromHexString("0xca11ee");
  // Callee code: PUSH1 1, PUSH1 0, SSTORE, PUSH1 0x2a, PUSH1 0, MSTORE, PUSH1 0x20, PUSH1 0, RETURN
  private static final Bytes CALLEE_CODE = Bytes.fromHexString("0x6001600055602a60005260206000f3");

  private static final BlockValues BLOCK_VALUES =
      new BlockValues() {
        @Override
        public Bytes getDifficultyBytes() {
          return Bytes.of(0x02, 0x00);
        }

        @Override
        public Bytes32 getMixHashOrPrevRandao() {
          return Bytes32.fromHexStringLenient("0x7a4d");
        }

        @Override
        public Optional<Wei> getBaseFee() {
          return Optional.of(Wei.of(7));
        }

        @Override
        public long getNumber() {
          return 1337;
        }

        @Override
        public long getTimestamp() {
          return 1_700_000_000L;
        }

        @Override
        public long getGasLimit() {
          return 60_000_000L;
        }

        @Override
        public long getSlotNumber() {
          return 42;
        }
      };

  private static final EvmConfiguration V1 = EvmConfiguration.DEFAULT;
  private static final EvmConfiguration V2 =
      new EvmConfiguration(
          EvmConfiguration.DEFAULT.jumpDestCacheWeightKB(),
          EvmConfiguration.DEFAULT.worldUpdaterMode(),
          EvmConfiguration.DEFAULT.enableOptimizedOpcodes(),
          true);

  private record Fork(String name, Function<EvmConfiguration, EVM> factory) {
    @Override
    public String toString() {
      return name;
    }
  }

  static final List<Fork> FORKS =
      List.of(
          new Fork("frontier", MainnetEVMs::frontier),
          new Fork("homestead", MainnetEVMs::homestead),
          new Fork("byzantium", MainnetEVMs::byzantium),
          new Fork("constantinople", MainnetEVMs::constantinople),
          new Fork("istanbul", MainnetEVMs::istanbul),
          new Fork("berlin", MainnetEVMs::berlin),
          new Fork("london", MainnetEVMs::london),
          new Fork("paris", MainnetEVMs::paris),
          new Fork("shanghai", MainnetEVMs::shanghai),
          new Fork("cancun", MainnetEVMs::cancun),
          new Fork("prague", MainnetEVMs::prague),
          new Fork("osaka", c -> MainnetEVMs.osaka(BigInteger.ONE, c)),
          new Fork(
              "amsterdam",
              c -> MainnetEVMs.amsterdam(new AmsterdamGasCalculator(), BigInteger.ONE, c)),
          new Fork(
              "bogota", c -> MainnetEVMs.bogota(new AmsterdamGasCalculator(), BigInteger.ONE, c)),
          new Fork(
              "future eips",
              c -> MainnetEVMs.futureEips(new AmsterdamGasCalculator(), BigInteger.ONE, c)));

  /**
   * The stacks each opcode runs on, top first: none at all, small numbers, which most operations
   * take as offsets, sizes and indices, the callee as the address PAY, BALANCE, the EXTCODE
   * operations and SELFDESTRUCT read, a call to the callee that sends value, and values at the top
   * of the word range.
   */
  private static final Map<String, List<Bytes>> STACKS =
      Map.of(
          "empty",
          List.of(),
          "small numbers",
          IntStream.rangeClosed(1, 17).mapToObj(Bytes::of).toList(),
          "callee",
          List.of(
              CALLEE.getBytes(),
              Bytes.of(1),
              Bytes.of(0),
              Bytes.of(0x20),
              Bytes.of(0),
              Bytes.of(0x20),
              Bytes.of(0)),
          "call with value",
          List.of(
              Bytes.of(0xff, 0xff),
              CALLEE.getBytes(),
              Bytes.of(1),
              Bytes.of(0),
              Bytes.of(0x20),
              Bytes.of(0),
              Bytes.of(0x20),
              Bytes.of(0)),
          "large values",
          List.of(
              Bytes32.ZERO.not(),
              Bytes.concatenate(Bytes.of(0x80), Bytes.wrap(new byte[31])),
              Bytes32.ZERO.not(),
              Bytes.of(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
              Bytes.of(1),
              Bytes.of(0),
              Bytes.of(0)));

  @ParameterizedTest(name = "{0}")
  @FieldSource("FORKS")
  void everyOpcodeEndsInTheSameStateOnV1AndV2(final Fork fork) {
    final EVM v1 = fork.factory().apply(V1);
    final EVM v2 = fork.factory().apply(V2);
    for (int opcode = 0; opcode < 0x100; opcode++) {
      for (final Map.Entry<String, List<Bytes>> stack : STACKS.entrySet()) {
        // the immediate of PUSH1, DUPN, SWAPN and EXCHANGE, followed by a STOP
        final Bytes code = Bytes.concatenate(push(stack.getValue()), Bytes.of(opcode, 0x01, 0x00));
        final String program =
            String.format("opcode 0x%02x on the %s stack", opcode, stack.getKey());
        final Outcome onV1 = run(v1, code, 1_000_000L, program);
        assertThat(run(v2, code, 1_000_000L, program)).as(program).isEqualTo(onV1);
        // and with exactly the gas the program needs, and one less
        final long used = 1_000_000L - onV1.remainingGas();
        for (long gas = Math.max(0, used - 1); gas <= used; gas++) {
          assertThat(run(v2, code, gas, program))
              .as("%s with %d gas", program, gas)
              .isEqualTo(run(v1, code, gas, program));
        }
      }
    }
  }

  /** Pushes the items, given top first, with one PUSH32 each. */
  private static Bytes push(final List<Bytes> topFirst) {
    final List<Bytes> pushes = new ArrayList<>();
    for (int i = topFirst.size() - 1; i >= 0; i--) {
      pushes.add(Bytes.of(0x7f));
      pushes.add(Bytes32.leftPad(topFirst.get(i)));
    }
    return Bytes.concatenate(pushes);
  }

  /** Records what EIP-7928 block access lists are built from. */
  private static final class AccessRecorder implements Eip7928AccessList {
    private final Set<String> accesses = new TreeSet<>();

    @Override
    public void addTouchedAccount(final Address address) {
      accesses.add(address.toHexString());
    }

    @Override
    public void addSlotAccessForAccount(final Address address, final UInt256 slotKey) {
      accesses.add(address.toHexString() + " " + slotKey.toHexString());
    }

    @Override
    public void clear() {
      accesses.clear();
    }
  }

  /**
   * Records the outermost frame's stack as the frame completes, the last moment it can be read, as
   * a v2 frame hands its stack back to the pool right after. It is disabled, so that execution
   * takes the untraced loops, as for block import.
   */
  private static final class StackCapture implements OperationTracer {
    private List<String> stack = List.of();

    @Override
    public boolean isEnabled() {
      return false;
    }

    @Override
    public void traceContextExit(final MessageFrame frame) {
      if (frame.getDepth() != 0) {
        return;
      }
      final List<String> items = new ArrayList<>();
      final long[] s = frame.stackDataV2();
      if (s != null) {
        for (int i = frame.stackTopV2() - 1; i >= 0; i--) {
          items.add(
              String.format(
                  "%016x%016x%016x%016x",
                  s[i << 2], s[(i << 2) + 1], s[(i << 2) + 2], s[(i << 2) + 3]));
        }
      } else {
        for (int i = 0; i < frame.stackSize(); i++) {
          items.add(Bytes32.leftPad(frame.getStackItem(i)).toUnprefixedHexString());
        }
      }
      stack = items;
    }
  }

  private static Outcome run(
      final EVM evm, final Bytes code, final long gas, final String program) {
    try {
      return run(evm, code, gas);
    } catch (final RuntimeException e) {
      throw new AssertionError(
          String.format(
              "%s with %d gas on %s",
              program, gas, evm.getEvmConfiguration().enableEvmV2() ? "v2" : "v1"),
          e);
    }
  }

  private static Outcome run(final EVM evm, final Bytes code, final long gas) {
    final ToyWorld world = new ToyWorld();
    final WorldUpdater setup = world.updater();
    setup.getOrCreate(CONTRACT).setBalance(Wei.of(1_000_000));
    setup.getOrCreate(CALLEE).setCode(CALLEE_CODE);
    setup.commit();
    final WorldUpdater updater = world.updater();
    final AccessRecorder accesses = new AccessRecorder();
    // as the transaction calling the contract has done, so that an operation touching the contract
    // before it fails on the stack makes no difference, just as in a block access list
    accesses.addTouchedAccount(CONTRACT);
    final StackCapture tracer = new StackCapture();
    final MessageFrame frame =
        MessageFrame.builder()
            .type(MessageFrame.Type.MESSAGE_CALL)
            .worldUpdater(updater)
            .initialGas(gas)
            .address(CONTRACT)
            .originator(CONTRACT)
            .gasPrice(Wei.ONE)
            .blobGasPrice(Wei.ONE)
            .inputData(Bytes.fromHexString("0x0102030405060708090a"))
            .sender(CONTRACT)
            .value(Wei.ZERO)
            .apparentValue(Wei.ZERO)
            .contract(CONTRACT)
            .code(new Code(code))
            .blockValues(BLOCK_VALUES)
            .completer(c -> {})
            .miningBeneficiary(Address.ZERO)
            .blockHashLookup((__, number) -> Hash.hash(Words.longBytes(number)))
            .eip7928AccessList(accesses)
            .build();
    final AbstractMessageProcessor call =
        new MessageCallProcessor(evm, new PrecompileContractRegistry());
    final AbstractMessageProcessor create = new ContractCreationProcessor(evm, true, List.of(), 1);
    final Deque<MessageFrame> frames = frame.getMessageFrameStack();
    while (!frames.isEmpty()) {
      final MessageFrame next = frames.peekFirst();
      (next.getType() == MessageFrame.Type.CONTRACT_CREATION ? create : call).process(next, tracer);
    }
    final boolean halted = frame.getExceptionalHaltReason().isPresent();
    return new Outcome(
        frame.getState(),
        frame.getExceptionalHaltReason().map(ExceptionalHaltReason::name).orElse(""),
        frame.getRemainingGas(),
        frame.getGasRefund(),
        frame.getStateGasUsed(),
        // a frame that halted exceptionally is discarded, its stack and memory with it
        halted ? List.of() : tracer.stack,
        halted ? "" : frame.shadowReadMemory(0, frame.memoryByteSize()).toHexString(),
        frame.getOutputData().toHexString(),
        frame.getLogs(),
        accounts(updater),
        accesses.accesses);
  }

  /** The balance, nonce, code and first storage slots of every account the program can reach. */
  private static Set<String> accounts(final WorldUpdater updater) {
    final Set<String> accounts = new TreeSet<>();
    for (final Account account : updater.getTouchedAccounts()) {
      accounts.add(describe(account));
    }
    for (final Address address : List.of(CONTRACT, CALLEE)) {
      Optional.ofNullable(updater.get(address))
          .ifPresent(account -> accounts.add(describe(account)));
    }
    return accounts;
  }

  private static String describe(final Account account) {
    return account.getAddress()
        + " balance "
        + account.getBalance().toShortHexString()
        + " nonce "
        + account.getNonce()
        + " code "
        + account.getCode().toHexString()
        + " storage "
        + account.getStorageValue(UInt256.ZERO).toShortHexString()
        + " "
        + account.getStorageValue(UInt256.ONE).toShortHexString();
  }

  private record Outcome(
      MessageFrame.State state,
      String haltReason,
      long remainingGas,
      long gasRefund,
      long stateGasUsed,
      List<String> stack,
      String memory,
      String output,
      List<Log> logs,
      Set<String> accounts,
      Set<String> accesses) {}
}
