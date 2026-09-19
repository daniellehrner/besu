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
 * The store's files are damaged in a way recovery must not paper over. The operator has to
 * re-migrate the store from its source.
 */
public final class CorruptCodeStoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is damaged and where
   */
  public CorruptCodeStoreException(final String message) {
    super(message + " - the code store is corrupt and must be re-migrated");
  }
}
