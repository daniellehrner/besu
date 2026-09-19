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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code MANIFEST} file: a small JSON document describing the store. Informational except for
 * {@code formatVersion} and {@code codeKeying}, which are checked on open.
 */
record Manifest(int formatVersion, String createdAt, String sourceBesuVersion, String codeKeying) {

  static final String FILE_NAME = "MANIFEST";
  static final int FORMAT_VERSION = 1;

  static Manifest loadOrCreate(final Path dir, final CodeStoreOptions options) throws IOException {
    final Path path = dir.resolve(FILE_NAME);
    if (Files.exists(path)) {
      final Manifest manifest = parse(Files.readString(path, StandardCharsets.UTF_8));
      if (manifest.formatVersion != FORMAT_VERSION) {
        throw new IllegalStateException(
            path + ": unsupported format version " + manifest.formatVersion);
      }
      if (!manifest.codeKeying.equals(options.codeKeying())) {
        throw new IllegalStateException(
            String.format(
                "%s: store was created with code keying '%s' but '%s' was requested",
                path, manifest.codeKeying, options.codeKeying()));
      }
      return manifest;
    }
    final Manifest manifest =
        new Manifest(
            FORMAT_VERSION,
            Instant.now().toString(),
            options.sourceBesuVersion(),
            options.codeKeying());
    manifest.write(dir);
    return manifest;
  }

  private void write(final Path dir) throws IOException {
    final Path tmp = dir.resolve(FILE_NAME + ".new");
    final byte[] json = toJson().getBytes(StandardCharsets.UTF_8);
    try (FileChannel ch =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ch.write(java.nio.ByteBuffer.wrap(json));
      ch.force(true);
    }
    Durable.rename(tmp, dir.resolve(FILE_NAME));
  }

  String toJson() {
    return String.format(
        "{%n  \"formatVersion\": %d,%n  \"createdAt\": \"%s\",%n  \"sourceBesuVersion\": \"%s\",%n"
            + "  \"codeKeying\": \"%s\"%n}%n",
        formatVersion, escape(createdAt), escape(sourceBesuVersion), escape(codeKeying));
  }

  static Manifest parse(final String json) {
    return new Manifest(
        Integer.parseInt(field(json, "formatVersion", "(\\d+)")),
        field(json, "createdAt", "\"([^\"]*)\""),
        field(json, "sourceBesuVersion", "\"([^\"]*)\""),
        field(json, "codeKeying", "\"([^\"]*)\""));
  }

  private static String field(final String json, final String name, final String valuePattern) {
    final Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*" + valuePattern).matcher(json);
    if (!m.find()) {
      throw new IllegalStateException("MANIFEST is missing field '" + name + "'");
    }
    return m.group(1);
  }

  // Values are version strings and timestamps; anything that would need escaping is dropped.
  private static String escape(final String value) {
    return value.replaceAll("[\"\\\\\\p{Cntrl}]", "");
  }
}
