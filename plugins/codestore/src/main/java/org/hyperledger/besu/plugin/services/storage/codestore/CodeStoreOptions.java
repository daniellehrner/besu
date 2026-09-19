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

/**
 * Tunables for {@link CodeStore#open}.
 *
 * @param logGrowStep bytes by which the log mapping grows, a positive multiple of 8
 * @param initialIndexCapacity slots in a freshly created index, a power of two
 * @param preload whether to pre-fault the log into memory on open
 * @param sourceBesuVersion recorded in the MANIFEST when the store is created
 * @param codeKeying recorded in the MANIFEST when the store is created
 * @param probeListener observes index probe counts
 */
public record CodeStoreOptions(
    long logGrowStep,
    long initialIndexCapacity,
    boolean preload,
    String sourceBesuVersion,
    String codeKeying,
    ProbeListener probeListener) {

  /** MANIFEST value for code keyed by its keccak hash. */
  public static final String CODE_HASH_KEYING = "code-hash";

  /**
   * Production defaults: 1 GiB log growth, 65,536 index slots, preload on.
   *
   * @return the default options
   */
  public static CodeStoreOptions defaults() {
    return new CodeStoreOptions(
        1L << 30, 1L << 16, true, "unknown", CODE_HASH_KEYING, ProbeListener.NONE);
  }

  /**
   * Copy with a different value.
   *
   * @param step the log mapping growth step in bytes
   * @return the changed options
   */
  public CodeStoreOptions withLogGrowStep(final long step) {
    return new CodeStoreOptions(
        step, initialIndexCapacity, preload, sourceBesuVersion, codeKeying, probeListener);
  }

  /**
   * Copy with a different value.
   *
   * @param capacity the slot count of a new index
   * @return the changed options
   */
  public CodeStoreOptions withInitialIndexCapacity(final long capacity) {
    return new CodeStoreOptions(
        logGrowStep, capacity, preload, sourceBesuVersion, codeKeying, probeListener);
  }

  /**
   * Copy with a different value.
   *
   * @param enabled whether to pre-fault the log on open
   * @return the changed options
   */
  public CodeStoreOptions withPreload(final boolean enabled) {
    return new CodeStoreOptions(
        logGrowStep, initialIndexCapacity, enabled, sourceBesuVersion, codeKeying, probeListener);
  }

  /**
   * Copy with a different value.
   *
   * @param besuVersion the Besu version to record in the MANIFEST
   * @return the changed options
   */
  public CodeStoreOptions withSourceBesuVersion(final String besuVersion) {
    return new CodeStoreOptions(
        logGrowStep, initialIndexCapacity, preload, besuVersion, codeKeying, probeListener);
  }

  /**
   * Copy with a different value.
   *
   * @param listener the probe count observer
   * @return the changed options
   */
  public CodeStoreOptions withProbeListener(final ProbeListener listener) {
    return new CodeStoreOptions(
        logGrowStep, initialIndexCapacity, preload, sourceBesuVersion, codeKeying, listener);
  }
}
