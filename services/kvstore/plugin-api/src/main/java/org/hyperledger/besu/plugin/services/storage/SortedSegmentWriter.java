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
package org.hyperledger.besu.plugin.services.storage;

import org.hyperledger.besu.plugin.services.exception.StorageException;

/**
 * Adds a run of entries to a segment that the caller hands over in ascending key order.
 *
 * <p>Knowing the order lets a storage write the entries once, in their final form, instead of
 * taking them through its regular write path. In exchange the entries are neither readable nor
 * durable before {@link #finish()}: a writer that is closed without it, or a process that stops,
 * loses them.
 *
 * <p>A writer is used by one thread at a time.
 */
public interface SortedSegmentWriter extends AutoCloseable {

  /**
   * Adds an entry. The key has to be greater than the key of the entry added before.
   *
   * @param key the key of the entry
   * @param value the value of the entry
   * @throws StorageException if the entry cannot be written
   */
  void put(byte[] key, byte[] value) throws StorageException;

  /**
   * The approximate size of the entries added so far.
   *
   * @return the size in bytes
   */
  long size();

  /**
   * Makes the entries part of the segment. They replace entries the segment already holds under the
   * same keys. The writer cannot be used afterwards.
   *
   * @throws StorageException if the entries cannot be added to the segment
   */
  void finish() throws StorageException;

  /** Releases the writer and discards its entries unless it was finished. */
  @Override
  void close();
}
