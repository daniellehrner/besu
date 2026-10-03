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
package org.hyperledger.besu.ethereum.mainnet;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.encoding.BlockAccessListDecoder;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.ethereum.rlp.RLP;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/**
 * Benchmark hook that runs blocks from before Amsterdam through the block access list processing
 * path, so that path can be measured on mainnet blocks and mainnet state while no such network
 * exists. A {@code record} import builds and stores the access list of every block; a {@code
 * replay} import hands each stored list to the block processor, which then executes the
 * transactions in parallel against it and computes the state root in the background, as it does for
 * an Amsterdam block. Off unless the {@code besu.bench.balReplay} system property is set, with the
 * lists kept in the directory named by {@code besu.bench.balReplay.dir}.
 */
public final class BlockAccessListReplay {

  /** What an import does with block access lists. */
  public enum Mode {
    /** Normal processing. */
    OFF,
    /** Build and store the access list of every imported block. */
    RECORD,
    /** Process every imported block with its stored access list. */
    REPLAY
  }

  private static final Mode MODE =
      Mode.valueOf(
          System.getProperty("besu.bench.balReplay", "off").trim().toUpperCase(Locale.ROOT));
  private static final Path DIR =
      Path.of(System.getProperty("besu.bench.balReplay.dir", "block-access-lists"));
  private static final boolean BAL_STATE_ROOT =
      !"accumulator".equals(System.getProperty("besu.bench.balReplay.stateRoot", "bal"));

  /**
   * Accepts a supplied list, since headers before Amsterdam carry no hash to check it against, and
   * requires the executed list to match it. Both lists are hashed, as an Amsterdam block hashes the
   * supplied and the executed list against its header, so the validation cost is the same.
   */
  public static final BlockAccessListValidator VALIDATOR =
      new BlockAccessListValidator() {
        @Override
        public boolean validate(
            final Optional<BlockAccessList> blockAccessList,
            final BlockHeader blockHeader,
            final int nbTransactions) {
          return true;
        }

        @Override
        public Optional<BlockAccessListValidationError> validateExecutedBlockAccessListAfterBuild(
            final BlockAccessList executedBal,
            final BlockHeader blockHeader,
            final Optional<BlockAccessList> suppliedBlockAccessList,
            final boolean logBalDetailsOnHashMismatch) {
          if (suppliedBlockAccessList.isEmpty()
              || BodyValidation.balHash(executedBal)
                  .equals(BodyValidation.balHash(suppliedBlockAccessList.get()))) {
            return Optional.empty();
          }
          return Optional.of(
              new BlockAccessListValidationError(
                  "Executed block access list differs from the replayed one for block "
                      + blockHeader.getNumber()));
        }
      };

  private BlockAccessListReplay() {}

  /**
   * The mode set for this JVM.
   *
   * @return the mode
   */
  public static Mode mode() {
    return MODE;
  }

  /**
   * Whether blocks get access lists although their fork has none.
   *
   * @return true when recording or replaying
   */
  public static boolean isEnabled() {
    return MODE != Mode.OFF;
  }

  /**
   * Whether replayed blocks get their state root from the access list, in the background, as an
   * Amsterdam block does. Set {@code besu.bench.balReplay.stateRoot=accumulator} to compute it from
   * the executed state instead, which checks the parallel execution against the block header.
   *
   * @return true for the access list root
   */
  public static boolean isBalStateRoot() {
    return BAL_STATE_ROOT;
  }

  /**
   * Reads the stored access list of a block.
   *
   * @param blockNumber the block number
   * @return the access list
   * @throws IllegalStateException if none was recorded, since replaying without it would measure
   *     the wrong processing path
   */
  public static BlockAccessList load(final long blockNumber) {
    final Path file = file(blockNumber);
    if (!Files.exists(file)) {
      throw new IllegalStateException("No recorded block access list at " + file);
    }
    try {
      return BlockAccessListDecoder.decode(
          new BytesValueRLPInput(Bytes.wrap(Files.readAllBytes(file)), false));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Stores the access list of a block for later replays.
   *
   * @param blockNumber the block number
   * @param blockAccessList the access list built while importing the block
   */
  public static void store(final long blockNumber, final BlockAccessList blockAccessList) {
    try {
      Files.createDirectories(DIR);
      Files.write(file(blockNumber), RLP.encode(blockAccessList::writeTo).toArrayUnsafe());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path file(final long blockNumber) {
    return DIR.resolve(blockNumber + ".rlp");
  }
}
