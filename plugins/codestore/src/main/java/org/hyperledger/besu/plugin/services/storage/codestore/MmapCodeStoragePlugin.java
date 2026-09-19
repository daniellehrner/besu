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

import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.StorageService;
import org.hyperledger.besu.plugin.services.exception.StorageException;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the {@code bonsai-mmap} key-value storage. */
public class MmapCodeStoragePlugin implements BesuPlugin {

  /** Name of the storage that serves every segment except code. */
  public static final String DELEGATE_NAME = "rocksdb";

  private static final Logger LOG = LoggerFactory.getLogger(MmapCodeStoragePlugin.class);

  private DelegatingKeyValueStorageFactory factory;

  /** Creates the plugin. */
  public MmapCodeStoragePlugin() {}

  @Override
  public void register(final ServiceManager context) {
    final StorageService storageService =
        context
            .getService(StorageService.class)
            .orElseThrow(
                () -> new IllegalStateException("No StorageService to register bonsai-mmap with"));
    // Resolved on first use, so the delegate may register after this plugin and its own CLI
    // options are parsed by the time it is asked for storage.
    factory =
        new DelegatingKeyValueStorageFactory(
            () ->
                storageService
                    .getByName(DELEGATE_NAME)
                    .orElseThrow(
                        () ->
                            new StorageException(
                                "bonsai-mmap needs the " + DELEGATE_NAME + " storage plugin")));
    storageService.registerKeyValueStorage(factory);
  }

  @Override
  public void start() {}

  @Override
  public void stop() {
    if (factory != null) {
      try {
        factory.close();
      } catch (final IOException e) {
        LOG.error("Failed to close the mmap code store", e);
      }
      factory = null;
    }
  }
}
