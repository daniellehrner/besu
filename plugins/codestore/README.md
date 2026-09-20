# Bonsai code store (`plugins:codestore`)

Stage 1 of a purpose-built storage engine for Besu: a memory-mapped, content-addressed,
append-only store for contract bytecode (`CODE_STORAGE`), to be delivered as a Besu plugin that
leaves every other segment on RocksDB.

Stage 1 is about correctness, crash safety and plumbing. It is not expected to make block
processing faster: code reads are served almost entirely from Besu's in-memory `BonsaiCodeCache`.

## Status

| Milestone | State |
|---|---|
| M1 standalone store, crash recovery, JMH | done |
| M2 Besu plugin wiring | built and unit/integration tested; testnet sync, reference and acceptance test runs still open |
| M3 migration tooling | not started |
| M4 metrics and benchmark report | not started |

## Besu version

The module lives in the Besu tree, on branch `feat/mmap-code-store`, started from `main` at
`7e05c23424` (2026-09-17). The source research in `NOTES-core-changes.md` was done against that
commit. Java 25. The engine uses `java.lang.foreign` only: no native code, no JNI, no incubator
modules. M1 was first built and accepted in a standalone repository and moved here afterwards; the
engine itself still depends on nothing in Besu except slf4j.

What was verified in that source, and where it differs from the original instructions, is in
`NOTES-core-changes.md`.

## Layout

```
src/main   the engine (CodeStore, CodeLog, CodeIndex) and the Besu wiring
           (MmapCodeStoragePlugin, DelegatingKeyValueStorageFactory,
           CodeRoutingKeyValueStorage, MmapCodeKeyValueStorage)
src/test   unit, recovery, property and SIGKILL tests
src/jmh    microbenchmarks
```

## Build and test

```
./gradlew :plugins:codestore:build      # compile, checks, fast tests (seconds)
./gradlew :plugins:codestore:slowTest   # M1 acceptance: 1M-pair property test, 50x SIGKILL test
./gradlew :plugins:codestore:jmh        # get latency, p50/p99
```

`slowTest` writes about 5 GiB under the system temp directory. Its size can be reduced with
`-Dcodestore.property.pairs=N` and `-Dcodestore.kill.iterations=N`.

## Running Besu on it

```
besu --data-storage-format=BONSAI --key-value-storage=bonsai-mmap ...
```

`MmapCodeStoragePlugin` is registered as a built-in next to the RocksDB plugin. The factory fetches
the registered `rocksdb` factory on first use, so every RocksDB option, including
`--Xplugin-rocksdb-high-spec-enabled`, applies unchanged. The code store lives in
`<data-path>/code-store/`.

| System property | Default | Effect |
|---|---|---|
| `bonsai.mmap.mirror` | `false` | also write code into RocksDB's own `CODE_STORAGE`, so that going back to `--key-value-storage=rocksdb` needs no migration. Costs the code a second time on disk |
| `bonsai.mmap.verify` | `false` | mirror, and check every code read against RocksDB; any difference fails the read |
| `bonsai.mmap.drop-delegate-code` | `false` | on a database that used to mirror: once every RocksDB code row is confirmed present in the code store, clear RocksDB's `CODE_STORAGE` |
| `bonsai.mmap.preload` | `false` | pre-fault the code log on open; off because on a log that is large next to RAM it evicts pages RocksDB is using. To be measured in M4 |

Rules the wiring enforces:

- **Code-hash keying only.** Every code write is checked for `keccak256(value) == key` before
  anything is written. Besu does not tell a storage plugin which keying it uses, and decides it by
  sampling the first row of `CODE_STORAGE`, which here is this store. A non-empty store therefore
  forces code-hash keying; on an empty store `--Xbonsai-code-using-code-hash-enabled=false` is
  refused at the first contract deployment, with nothing committed.
- **Code first.** Bonsai commits accounts, storage, trie nodes and code in one transaction. Across
  two backends that cannot be atomic, so the code store is synced first and RocksDB commits second.
  Code without state that names it is inert; state naming code that is missing would be corruption,
  and cannot happen in this order.
- **One copy of the code.** By default RocksDB never sees code. The first such start leaves a
  `SOLE_COPY` marker in the code store directory, written durably before any code is. From then on
  Besu refuses any other `--key-value-storage` on that data directory, and the store refuses
  `mirror` and `verify`, because RocksDB no longer holds all the code. Going back to plain RocksDB
  needs the reverse migration (M3).
- **No start on an unmigrated database.** An empty code store next to a RocksDB that already holds
  code is refused: every code read would miss.
- **Snapshots** read code from the live store; their writes stay in the snapshot.
- Removing a single code entry is an error (it only happens under account-hash keying). `clear` is
  supported. Streams are in insertion order, and ordered lookups on code are unsupported; Besu uses
  neither on this segment.

## On-disk format

Three files in the store directory, plus a `LOCK` file held while the store is open. All
multi-byte fields are big-endian.

- `code.log`: 64-byte header (`BCS1`, version 1), then 8-byte-aligned records of
  `REC\0 | length u32 | codeHash[32] | payload | crc32c(codeHash ‖ payload) | zero padding`.
  Records are immutable. While the store is open the file is longer than the log (it is extended
  sparsely in 1 GiB steps ahead of the read-only mapping) and is zero past the log end; a clean
  close truncates it to the exact length.
- `code.idx`: 64-byte header (`BCSI`, version 1, capacity, count, logLength), then 40-byte slots of
  `codeHash[32] | payload offset u64`. Open addressing, linear probing, power-of-two capacity, load
  factor at most 0.5, probe start taken from the first 8 bytes of the hash. Derived: it can always
  be rebuilt from the log. Grows by building `code.idx.new` and renaming it over.
- `MANIFEST`: JSON with format version, creation time, source Besu version and code keying.

### Durability and recovery

Reads go through a read-only mapping, writes through the file channel (`write` + `force`), never
through the mapping. `put` appends to the log and promises nothing. `sync` forces the log, then writes the index slots,
then advances and forces the index header's `logLength`. The index on disk therefore never points
at log bytes that were not forced first, and `logLength` is a durable watermark.

On open the log is scanned forward from the watermark. Valid records are re-indexed. The first
thing that is not a valid record ends the log; what follows was never acknowledged as synced and is
discarded, with the byte count logged at INFO. Damage below the watermark is corruption: `open`
fails if it can see it (index missing, or log shorter than the index claims), `verify()` finds the
rest. A failed open never modifies the log.

A slot of the index can straddle two pages, and after a power failure only one of them may be on
disk. On an unclean open every slot written since the last commit is checked against the log, and
the index is rebuilt if one does not match.

### mmap, and what "Are You Sure You Want to Use MMAP in Your DBMS?" means here

Crotty, Leis and Pavlo (CIDR 2022) argue against mmap as a buffer pool replacement. Their "maybe"
case is a read-only working set that fits in memory, which is what this store is: immutable
records, a small hot set, and Besu's code cache in front. How their four problems land:

1. *Transactional safety*: the OS may flush dirty mapped pages at any time. The log is not written
   through a mapping at all. The index is, but it is derived, its slots are written only after the
   log is forced, and torn slots are repaired as described above.
2. *I/O stalls*: a cold read is a blocking page fault, as a RocksDB block-cache miss is a blocking
   `pread`. Specific to the JVM: a thread faulting inside a mapped access may hold up a safepoint
   for the duration of the fault.
3. *Error handling*: reads do not check the CRC (see below), a disk read error surfaces as an
   `InternalError` rather than an exception, and both are accepted. A full disk is an
   `IOException`, because writes do not go through the mapping.
4. *Eviction cost* (page table contention, kswapd, TLB shootdowns) appears only when the code set
   exceeds the free page cache, at rates far below the paper's. M4 measures it: replay the
   devnet-8 JUMPDEST attack under a cgroup memory limit and record TLB shootdowns, kswapd CPU,
   major faults and time-to-safepoint.

In Stage 1 mmap buys nothing over `pread`, because the storage SPI forces a copy to `byte[]`. It is
kept for the zero-copy `Code` of a later stage, the only thing that removes the 64 KB allocate,
zero-fill and copy per cold call. None of this carries over to a flat account/storage engine:
mutable, far larger than memory, random point reads of tiny values. That wants an explicit
off-heap page cache with positional reads.

### Reads do not check the CRC

`get` trusts the record; the CRC is checked by the recovery scan and by `verify()`. Decided
2026-09-19. Measured cost of checking, per cold 64 KB record in a sequential sweep (unpinned
laptop): CRC32C 1.7 µs alone, 0.8 µs on top of the 4.6 µs copy to `byte[]`. That is 1-2.5% of a
devnet-8 JUMPDEST-attack block (~57k cold 64 KB code loads), so it is affordable in Stage 1, but it
would force the whole record through memory on every read and so defeat a zero-copy `view` later.
If read-side checking is wanted, verify each record once per process (a bitset with one bit per
index slot) rather than on every read. Revisit when `view` is promoted.
