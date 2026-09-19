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
package org.hyperledger.besu.plugin.services.storage.codestore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Filesystem operations that survive a crash. */
final class Durable {
  private Durable() {}

  /** Atomically renames {@code from} over {@code to} and makes the rename itself durable. */
  static void rename(final Path from, final Path to) throws IOException {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    syncDirectory(to.toAbsolutePath().getParent());
  }

  static void syncDirectory(final Path dir) throws IOException {
    try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
      ch.force(true);
    }
  }
}
