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
package org.hyperledger.besu.plugin.services.storage.rocksdb.segmented;

import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;
import org.hyperledger.besu.plugin.services.storage.SortedSegmentWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.rocksdb.EnvOptions;
import org.rocksdb.IngestExternalFileOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDBException;
import org.rocksdb.SstFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a sorted run of entries into a table file beside the database and hands the finished file
 * to RocksDB, which links it into the column family as it is.
 *
 * <p>The entries skip the write-ahead log and the memtable, and a file whose key range holds
 * nothing else in the column family lands in the bottom level, so they are written exactly once. A
 * file whose range overlaps existing keys is placed above them and compacted like any other.
 */
final class RocksDBSortedSegmentWriter implements SortedSegmentWriter {

  private static final Logger LOG = LoggerFactory.getLogger(RocksDBSortedSegmentWriter.class);

  private static final AtomicLong NEXT_FILE = new AtomicLong();

  private final RocksDBColumnarKeyValueStorage storage;
  private final SegmentIdentifier segment;
  private final Path file;
  // the ingestion only accepts files written with the column family's own comparator and
  // compression, which the column family options carry
  private final Options sstOptions;
  private final EnvOptions envOptions = new EnvOptions();
  private final SstFileWriter writer;
  private boolean opened;
  private boolean done;
  private long size;
  private byte[] firstKey;
  private byte[] lastKey;

  RocksDBSortedSegmentWriter(
      final RocksDBColumnarKeyValueStorage storage, final SegmentIdentifier segment) {
    this.storage = storage;
    this.segment = segment;
    this.file =
        stagingDirectory(storage.configuration.getDatabaseDir())
            .resolve(String.format("%s-%012d.sst", segment.getName(), NEXT_FILE.getAndIncrement()));
    this.sstOptions = new Options(storage.options, storage.columnFamilyOptions(segment));
    this.writer = new SstFileWriter(envOptions, sstOptions);
  }

  /**
   * The staging directory sits beside the database, on the same file system, so the ingestion can
   * move the files in with a rename instead of copying them.
   */
  static Path stagingDirectory(final Path databaseDir) {
    return databaseDir.resolveSibling(databaseDir.getFileName() + "-sorted");
  }

  /**
   * Removes the files of writers that were not finished when the process stopped. Their entries
   * were never part of the database, so nothing refers to them.
   */
  static void deleteLeftovers(final Path databaseDir) {
    final Path directory = stagingDirectory(databaseDir);
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (Stream<Path> files = Files.walk(directory)) {
      for (final Path path : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (final IOException e) {
      LOG.warn("The unfinished sorted files in {} could not be removed", directory, e);
    }
  }

  @Override
  public void put(final byte[] key, final byte[] value) {
    storage.throwIfClosed();
    try {
      if (!opened) {
        Files.createDirectories(file.getParent());
        writer.open(file.toString());
        opened = true;
        firstKey = key;
      }
      writer.put(key, value);
      lastKey = key;
      size += key.length + value.length;
    } catch (final RocksDBException | IOException e) {
      throw new StorageException(e);
    }
  }

  @Override
  public long size() {
    return size;
  }

  @Override
  public void finish() {
    storage.throwIfClosed();
    if (done) {
      throw new IllegalStateException("The sorted writer was already finished or closed");
    }
    // RocksDB refuses to finish a file without entries, and there is nothing to ingest anyway
    if (opened) {
      try (final IngestExternalFileOptions options =
          new IngestExternalFileOptions().setMoveFiles(true)) {
        writer.finish();
        storage
            .getDB()
            .ingestExternalFile(
                storage.safeColumnHandle(segment), List.of(file.toString()), options);
      } catch (final RocksDBException e) {
        throw new StorageException(e);
      }
      // together with the level RocksDB reports for the file in its own log, this tells why a file
      // could not be kept in the bottom level
      LOG.atDebug()
          .setMessage("Stored sorted file {} with {} bytes of entries from {} to {}")
          .addArgument(file::getFileName)
          .addArgument(size)
          .addArgument(() -> Bytes.wrap(firstKey).toHexString())
          .addArgument(() -> Bytes.wrap(lastKey).toHexString())
          .log();
    }
    done = true;
    release();
  }

  @Override
  public void close() {
    if (!done) {
      done = true;
      release();
    }
    // a finished file was moved into the database, this only removes one that was not
    try {
      Files.deleteIfExists(file);
    } catch (final IOException e) {
      LOG.warn("The unfinished sorted file {} could not be removed", file, e);
    }
  }

  private void release() {
    writer.close();
    envOptions.close();
    sstOptions.close();
  }
}
