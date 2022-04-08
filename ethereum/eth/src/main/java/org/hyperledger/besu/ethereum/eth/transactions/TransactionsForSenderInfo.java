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

package org.hyperledger.besu.ethereum.eth.transactions;

import org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter;
import org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter.TransactionInfo;

import java.math.BigInteger;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Stream;

public class TransactionsForSenderInfo {
  private final NavigableMap<BigInteger, AbstractPendingTransactionsSorter.TransactionInfo>
      transactionsInfos;
  private Optional<BigInteger> nextGap = Optional.empty();

  public TransactionsForSenderInfo() {
    transactionsInfos = new TreeMap<>();
  }

  public void addTransactionToTrack(
      final BigInteger nonce, final AbstractPendingTransactionsSorter.TransactionInfo transactionInfo) {
    synchronized (transactionsInfos) {
      if (!transactionsInfos.isEmpty()) {
        final BigInteger expectedNext = transactionsInfos.lastKey().add(BigInteger.ONE);
        if (nonce.compareTo(expectedNext) > 0 && nextGap.isEmpty()) {
          nextGap = Optional.of(expectedNext);
        }
      }
      transactionsInfos.put(nonce, transactionInfo);
      if (nonce.equals(nextGap.orElse(BigInteger.valueOf(-1)))) {
        findGap();
      }
    }
  }

  public void removeTrackedTransaction(final BigInteger nonce) {
    transactionsInfos.remove(nonce);
    synchronized (transactionsInfos) {
      if (!transactionsInfos.isEmpty() && !nonce.equals(transactionsInfos.firstKey())) {
        findGap();
      }
    }
  }

  private void findGap() {
    // find first gap
    BigInteger expectedValue = transactionsInfos.firstKey();
    for (final BigInteger nonce : transactionsInfos.keySet()) {
      if (expectedValue.equals(nonce)) {
        // no gap, keep moving
        expectedValue = expectedValue.add(BigInteger.ONE);
      } else {
        nextGap = Optional.of(expectedValue);
        return;
      }
    }
    nextGap = Optional.empty();
  }

  public Optional<BigInteger> maybeNextNonce() {
    if (transactionsInfos.isEmpty()) {
      return Optional.empty();
    } else {
      return nextGap.isEmpty() ? Optional.of(transactionsInfos.lastKey().add(BigInteger.ONE)) : nextGap;
    }
  }

  public Stream<TransactionInfo> streamTransactionInfos() {
    return transactionsInfos.values().stream();
  }

  public TransactionInfo getTransactionInfoForNonce(final BigInteger nonce) {
    return transactionsInfos.get(nonce);
  }
}
