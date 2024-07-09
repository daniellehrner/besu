/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.evm.account;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

import java.util.Map;
import java.util.NavigableMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/** Wraps another mutable account that has authorized code to be loaded into it. */
public class MutableAuthorizedCodeAccount implements MutableAccount {

  private final MutableAccount wrappedAccount;
  private final Bytes authorizedCode;

  /** The hash of the authorized code. */
  protected Hash codeHash = null;

  /**
   * Creates a new MutableAuthorizedCodeAccount.
   *
   * @param wrappedAccount the account that has authorized code to be loaded into it.
   * @param authorizedCode the authorized code.
   */
  public MutableAuthorizedCodeAccount(
      final MutableAccount wrappedAccount, final Bytes authorizedCode) {
    this.wrappedAccount = wrappedAccount;
    this.authorizedCode = authorizedCode;
  }

  @Override
  public Address getAddress() {
    return wrappedAccount.getAddress();
  }

  @Override
  public boolean isStorageEmpty() {
    return wrappedAccount.isStorageEmpty();
  }

  @Override
  public Hash getAddressHash() {
    return wrappedAccount.getAddressHash();
  }

  @Override
  public long getNonce() {
    return wrappedAccount.getNonce();
  }

  @Override
  public Wei getBalance() {
    return wrappedAccount.getBalance();
  }

  @Override
  public Bytes getCode() {
    return authorizedCode;
  }

  @Override
  public Hash getCodeHash() {
    if (codeHash == null) {
      codeHash = authorizedCode.equals(Bytes.EMPTY) ? Hash.EMPTY : Hash.hash(authorizedCode);
    }

    return codeHash;
  }

  @Override
  public UInt256 getStorageValue(final UInt256 key) {
    return wrappedAccount.getStorageValue(key);
  }

  @Override
  public UInt256 getOriginalStorageValue(final UInt256 key) {
    return wrappedAccount.getOriginalStorageValue(key);
  }

  @Override
  public NavigableMap<Bytes32, AccountStorageEntry> storageEntriesFrom(
      final Bytes32 startKeyHash, final int limit) {
    return wrappedAccount.storageEntriesFrom(startKeyHash, limit);
  }

  @Override
  public void setNonce(final long value) {
    wrappedAccount.setNonce(value);
  }

  @Override
  public void setBalance(final Wei value) {
    wrappedAccount.setBalance(value);
  }

  @Override
  public void setCode(final Bytes code) {
    throw new RuntimeException("Cannot set code on an AuthorizedCodeAccount");
  }

  @Override
  public void setStorageValue(final UInt256 key, final UInt256 value) {
    wrappedAccount.setStorageValue(key, value);
  }

  @Override
  public void clearStorage() {
    wrappedAccount.clearStorage();
  }

  @Override
  public Map<UInt256, UInt256> getUpdatedStorage() {
    return wrappedAccount.getUpdatedStorage();
  }

  @Override
  public void becomeImmutable() {
    wrappedAccount.becomeImmutable();
  }
}
