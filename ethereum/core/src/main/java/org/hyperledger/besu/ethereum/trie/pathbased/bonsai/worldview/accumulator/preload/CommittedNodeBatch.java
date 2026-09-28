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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.preload;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Trie nodes produced by one state root computation, to be added to the node cache. Every node on a
 * modified path gets a new hash, so without this the next block's walk down that path misses at
 * each level and reads back what was just written. The cache is keyed by hash, so an entry stays
 * valid even if the block that produced it is never persisted.
 *
 * <p>Nodes are collected here rather than cached one by one because the trie pools commit
 * concurrently and would contend on the cache's segment locks.
 */
public class CommittedNodeBatch {
  private final ConcurrentLinkedQueue<Map.Entry<Bytes32, Bytes>> accountNodes =
      new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<Map.Entry<Bytes32, Bytes>> storageNodes =
      new ConcurrentLinkedQueue<>();

  public void addAccountNode(final Bytes32 nodeHash, final Bytes node) {
    accountNodes.add(Map.entry(nodeHash, node));
  }

  public void addStorageNode(final Bytes32 nodeHash, final Bytes node) {
    storageNodes.add(Map.entry(nodeHash, node));
  }

  boolean isEmpty() {
    return accountNodes.isEmpty() && storageNodes.isEmpty();
  }

  Iterable<Map.Entry<Bytes32, Bytes>> accountNodes() {
    return accountNodes;
  }

  Iterable<Map.Entry<Bytes32, Bytes>> storageNodes() {
    return storageNodes;
  }
}
