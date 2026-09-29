# Notes on Besu core: findings, open decisions, possible core changes

Nothing in Besu core has been changed; the module was added beside it. Everything below was read
from Besu `main` at
`7e05c23424` (2026-09-17, `git describe`: `26.7.0-301-g7e05c23424`, plugin API baseline
`26.8.1`). Paths are relative to the Besu repository.

## Findings that confirm the design

- **Code-hash keying is the default.** `ExtraStorageConfiguration.DEFAULT_CODE_USING_CODE_HASH_ENABLED
  = true`. The hidden flag `--Xbonsai-code-using-code-hash-enabled` still exists
  (`ExtraStorageOptions`). The strategy classes now live in
  `ethereum/core/.../trie/pathbased/bonsai/storage/code/`.
- **Code is never deleted under code-hash keying.** `CodeHashCodeStorageStrategy.removeFlatCode` is
  an empty method. SELFDESTRUCT and account clearing do reach `Updater.removeCode` (from the three
  `StateRootCommitter` implementations), and it does nothing. There is no reference counting
  anywhere. The append-only design holds; index tombstones are not needed.
  (`AccountHashCodeStorageStrategy.removeFlatCode` is a real delete, which is one more reason to
  refuse account-hash keying.)
- **Empty code is never written.** `Updater.putCode` returns early for empty code, so
  `Hash.EMPTY` does not appear as a key. The store supports zero-length payloads anyway.
- **Snap sync writes code through the normal path**: `BytecodeRequest`, `SnapV2BytecodeRequest`
  and `SnapV2BlockAccessListApplier` all end in `Updater.putCode` and so in
  `transaction.put(CODE_STORAGE, ...)`. Nothing bypasses the storage SPI.
- **Snap sync and resync keep `CODE_STORAGE`**: `clearFlatDatabase()` → `resetOnResync()` skips it.
- **`BonsaiCodeCache`** is a fixed 256 MB Caffeine cache with `recordStats()`; the hit rate is
  already exported as `bonsai_cache_code_cache_hit_rate`.

## Decisions taken (2026-09-19)

- **Code keying**: build for code-hash keying only and enforce `keccak256(value) == key` on every
  write; no account-hash mapping, no plugin API change. The protocol fixes the hash in the account,
  not how a client keys its code table, but every client keys by it.
- **The four-segment transaction**: code store first, RocksDB second. The order is the safety
  mechanism, see below.
- **Mirroring** into RocksDB's `CODE_STORAGE` was the default while M2 was validated, so that the
  fallback was one flag. It is now opt-in (`-Dbonsai.mmap.mirror=true`): the code store holds the
  only copy, a `SOLE_COPY` marker records that, and `BesuCommand` refuses to open such a data
  directory with any other storage.
- **Snapshots** turned out simpler than proposed below: RocksDB snapshots hand out a no-op
  transaction, so no overlay is needed. Code reads go to the live store, writes to the snapshot's
  own transaction.
- **Core changes made**: `BesuCommand` registers `MmapCodeStoragePlugin` as a built-in and stops it
  after the runner has closed, and checks `requireCodeReachable` before building the storage
  provider; `app` depends on `:plugins:codestore`. `MappedCodeStorage` (in
  `.../bonsai/storage/code/`) lets a storage serve a code value where it lies, and the two
  code-hash strategies build their `Code` from it when the storage offers it, which is one copy of
  the code instead of two; see the in-place read section of the README. Test plumbing only: the reference tests build
  their storage through `ReferenceTestStorageProvider` (`-Dbesu.reftest.mmapCodeStoreDir=DIR` puts
  code on an mmap store in verify mode), and the acceptance-test process runner takes its storage
  from `-Dacctests.keyValueStorage`.

## Stop-and-ask condition 1: the code-keying strategy is not visible to a plugin

The plugin-facing `DataStorageConfiguration` exposes the database format, receipt compaction,
history-expiry pruning and revert reasons, nothing else. `BesuConfigurationImpl` deliberately does
not forward `getExtraStorageConfiguration()`. No other plugin service exposes it.

Besu itself does not persist the choice either. `FlatDbStrategyProvider.detectCodeStorageByHash()`
sniffs it at startup: it streams one entry of `CODE_STORAGE` and checks `keccak(value) == key`; the
database wins over the configuration, which only applies when the segment is empty.

Options, none of which needs a core change:

1. **Enforce instead of detect (recommended).** The plugin *is* the `CODE_STORAGE` backend, so it
   sees every write. Check `keccak256(value) == key` on each put and fail the transaction loudly on
   a mismatch. Account-hash keying is then refused at the first code write rather than at start-up,
   but it can never silently mis-key data. Cost is one keccak per new contract, against an fsync.
   Because Besu's own sniff reads our store, a non-empty mmap store also forces Besu into
   code-hash mode regardless of the flag.
2. Additionally sniff the embedded RocksDB `CODE_STORAGE` at start-up the way Besu does and refuse
   to start if it holds account-hash keyed rows. This catches a pre-existing account-hash database
   before any write.

A core change that would make this clean: expose code keying on the plugin-facing
`DataStorageConfiguration`.

## Stop-and-ask condition 2: `CODE_STORAGE` is requested inside a multi-segment list

`BonsaiWorldStateKeyValueStorage` asks the provider for **one** `SegmentedKeyValueStorage` over
`[ACCOUNT_INFO_STATE, CODE_STORAGE, ACCOUNT_STORAGE_STORAGE, TRIE_BRANCH_STORAGE]`, and
`Updater.commit()` commits **one** transaction across all four. With RocksDB that is a single
atomic write batch. Routing `CODE_STORAGE` elsewhere splits that transaction across two backends;
it cannot be atomic.

Two further constraints come with the composed store:

- It is cast, unchecked, to `SnappableKeyValueStorage`, and its snapshot to
  `SnappedKeyValueStorage` (`BonsaiSnapshotWorldStateKeyValueStorage`). A routing store that does
  not implement both fails with `ClassCastException` at runtime.
  `isSnapshotIsolationSupported()` is never consulted.
- `BonsaiFlatDbStrategy.clearAll()` calls `clear(CODE_STORAGE)`, and the keying sniff calls
  `stream(CODE_STORAGE)`. Both must work.

Proposed resolution, relying on content addressing rather than on atomicity:

- The routing transaction buffers `CODE_STORAGE` puts. On `commit()` it appends and **syncs the
  code store first**, then commits the RocksDB batch. On `rollback()` it drops the buffer.
- Invariant: *code is durable before any state that references it*. A crash between the two
  commits leaves code records that no account references. That is harmless and is the same state
  Besu already produces on every SELFDESTRUCT, since removal is a no-op. The reverse order would
  be unsafe and is never used. A failed code commit aborts before RocksDB is touched.
- Snapshots: records are immutable and content-addressed, so a snapshot of the code segment can be
  the live store plus an in-memory overlay for the snapshot's own writes. A snapshot may be able to
  read code added after it was taken, but only by a hash it has no account pointing to.
- `remove(CODE_STORAGE, ...)` cannot occur under code-hash keying; the routing transaction throws
  if it ever does, rather than ignoring it.

This weakens "atomic" to "ordered and idempotent" for one segment. It needs a decision before M2.

## Other facts M2/M3 must respect

- `RocksDBKeyValueStorageFactory` is public and embeddable (evmtool and several tests do it), but
  it is published as `org.hyperledger.besu.internal:besu-plugins-rocksdb`: internal, no API
  stability promise, and it drags in `:ethereum:core`. The plugin will depend on Besu internals.
- `create(List<SegmentIdentifier>, ...)` is called many times with different subsets; RocksDB
  returns one shared instance over all configured segments for every call.
- The storage registry is a map keyed by factory name; a second registration under the same name
  silently replaces the first.
- Plugins cannot register CLI subcommands (`PicoCLIOptions` only adds option mixins, and enforces a
  `--plugin-<ns>-` / `--Xplugin-<ns>-` prefix). Migration has to be a standalone CLI, which is what
  `tools/migrate` in the deliverables already implies.

## Deviations from the written instructions

- **Project layout**: a module inside the Besu repository (`plugins/codestore`) rather than a
  separate plugin project, decided 2026-09-19. It may depend on Besu code. That removes the
  concern above about `besu-plugins-rocksdb` being an internal artifact, and it means the plugin
  can be registered as a built-in the way `RocksDBPlugin` is.
- **Byte order**: the format section does not name one. Everything is big-endian, which is what
  makes the magics read as `BCS1`, `BCSI` and `REC\0` on disk.
- **File length vs log length**: `code.log` is mapped read-only in 1 GiB steps, and mapping past
  the end of a file extends it (sparsely). Writes go through the file channel, not the mapping, so
  §4.2's `READ_WRITE` mapping and `MemorySegment.force()` are not used for the log: see the mmap
  section of the README. While the store is open the file is therefore longer than the log,
  with zeros past the end; a clean close truncates it back. "Truncate the trailing partial record"
  is implemented as zeroing the torn bytes, and the byte count logged at INFO is the torn bytes,
  not the zero padding.
- **The index format** (§3.2) uses 16-byte slots (8-byte hash prefix + offset) rather than full
  hashes, is loaded into memory at open, and the log mapping gets `madvise(MADV_RANDOM)` through
  an FFM downcall; measured reasons in the README.
- **`MemorySegment.load()` after open** (§4.2) is off by default, `-Dbonsai.mmap.preload=true`
  turns it on; M4 is to report whether it helps.
- **Atomicity wording in §4.4**: log append order does not make a multi-record commit atomic; a
  kill half-way through leaves the first records of the batch in the log, and recovery keeps them.
  That is safe only because records are content-addressed (see above), and the class javadoc says
  so rather than claiming atomicity.
