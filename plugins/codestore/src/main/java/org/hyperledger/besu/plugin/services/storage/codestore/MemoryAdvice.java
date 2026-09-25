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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code madvise(2)} for mapped segments, through a foreign downcall. Called once per mapping,
 * never on a read. Silently does nothing where the call is unavailable.
 */
final class MemoryAdvice {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryAdvice.class);

  /** System property; {@code false} leaves the kernel's readahead on for the code log. */
  static final String PROPERTY = "bonsai.mmap.random-access";

  private static final int MADV_RANDOM = 1;
  private static final MethodHandle MADVISE = lookup();

  private MemoryAdvice() {}

  private static MethodHandle lookup() {
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
      return null;
    }
    try {
      final Linker linker = Linker.nativeLinker();
      return linker
          .defaultLookup()
          .find("madvise")
          .map(
              address ->
                  linker.downcallHandle(
                      address,
                      FunctionDescriptor.of(
                          ValueLayout.JAVA_INT,
                          ValueLayout.ADDRESS,
                          ValueLayout.JAVA_LONG,
                          ValueLayout.JAVA_INT)))
          .orElse(null);
    } catch (final RuntimeException | LinkageError e) {
      LOG.debug("madvise is not available", e);
      return null;
    }
  }

  /** Tells the kernel that {@code segment} is accessed randomly, which turns readahead off. */
  static void random(final MemorySegment segment) {
    if (MADVISE == null || !Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))) {
      return;
    }
    try {
      final int result = (int) MADVISE.invokeExact(segment, segment.byteSize(), MADV_RANDOM);
      if (result != 0) {
        LOG.debug("madvise(MADV_RANDOM) returned {}", result);
      }
    } catch (final Throwable t) {
      LOG.debug("madvise(MADV_RANDOM) failed", t);
    }
  }
}
