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
package org.hyperledger.besu.cli.subcommands.storage;

import static org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.BaseVersionedStorageFormat.BONSAI_ARCHIVE_WITH_JUMPDEST_ANALYSIS;
import static org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.BaseVersionedStorageFormat.BONSAI_WITH_JUMPDEST_ANALYSIS;

import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.DatabaseMetadata;
import org.hyperledger.besu.plugin.services.storage.rocksdb.configuration.VersionedStorageFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Tells the operator how to undo the upgrade to code stored with its analysis, once it has
 * happened. The arguments of this process are not repeated: they can hold secrets and miss the
 * configuration given by environment variables.
 */
public final class CodeFormatDowngradeWarning {

  private CodeFormatDowngradeWarning() {}

  /**
   * Reads the storage format the database metadata records, to be compared before and after the
   * storage is opened.
   *
   * @param dataDir the data directory
   * @return the recorded format, empty when there is no database yet
   */
  public static Optional<VersionedStorageFormat> storageFormat(final Path dataDir) {
    try {
      if (!DatabaseMetadata.isPresent(dataDir)) {
        return Optional.empty();
      }
      return Optional.of(DatabaseMetadata.lookUpFrom(dataDir).getVersionedStorageFormat());
    } catch (final IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * The warning to log when opening the storage upgraded the database to code stored with its
   * analysis.
   *
   * @param dataDir the data directory
   * @param before the storage format recorded before the storage was opened
   * @return the warning, empty when nothing was upgraded
   */
  public static Optional<String> after(
      final Path dataDir, final Optional<VersionedStorageFormat> before) {
    return after(dataDir, before, storageFormat(dataDir));
  }

  static Optional<String> after(
      final Path dataDir,
      final Optional<VersionedStorageFormat> before,
      final Optional<VersionedStorageFormat> after) {
    if (before.isEmpty() || after.isEmpty() || before.get().equals(after.get())) {
      return Optional.empty();
    }
    if (after.get() != BONSAI_WITH_JUMPDEST_ANALYSIS
        && after.get() != BONSAI_ARCHIVE_WITH_JUMPDEST_ANALYSIS) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            "The database in %1$s was upgraded from %2$s to %3$s, which Besu versions before this"
                + " one cannot open. To downgrade Besu, stop it and revert the database first: run"
                + " `besu storage revert-code-format` as the user Besu runs as, with the same"
                + " options, config file and BESU_* environment variables as the node, so that it"
                + " opens the database in %1$s",
            dataDir.toAbsolutePath(), describe(before.get()), describe(after.get())));
  }

  private static String describe(final VersionedStorageFormat format) {
    return format.getFormat() + " version " + format.getVersion();
  }
}
