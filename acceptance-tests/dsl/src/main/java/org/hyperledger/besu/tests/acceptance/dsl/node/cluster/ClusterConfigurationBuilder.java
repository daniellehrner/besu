/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.tests.acceptance.dsl.node.cluster;

public class ClusterConfigurationBuilder {
  private boolean awaitPeerDiscovery = true;
  private int peerDiscoveryTimeoutSeconds = 60; // Default 60 second timeout

  public ClusterConfigurationBuilder awaitPeerDiscovery(final boolean awaitPeerDiscovery) {
    this.awaitPeerDiscovery = awaitPeerDiscovery;
    return this;
  }

  public ClusterConfigurationBuilder peerDiscoveryTimeout(final int timeoutSeconds) {
    this.peerDiscoveryTimeoutSeconds = timeoutSeconds;
    return this;
  }

  public ClusterConfiguration build() {
    return new ClusterConfiguration(awaitPeerDiscovery, peerDiscoveryTimeoutSeconds);
  }
}
