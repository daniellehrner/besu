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
| `bonsai.mmap.mirror` | `true` | also write code into RocksDB's own `CODE_STORAGE`, so that going back to `--key-value-storage=rocksdb` needs no migration |
| `bonsai.mmap.verify` | `false` | mirror, and check every code read against RocksDB; any difference fails the read |
| `bonsai.mmap.preload` | `true` | pre-fault the code log on open |

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
  Records are immutable. While the store is open the file is longer than the log (it is mapped in
  1 GiB steps) and is zero past the log end; a clean close truncates it to the exact length.
- `code.idx`: 64-byte header (`BCSI`, version 1, capacity, count, logLength), then 40-byte slots of
  `codeHash[32] | payload offset u64`. Open addressing, linear probing, power-of-two capacity, load
  factor at most 0.5, probe start taken from the first 8 bytes of the hash. Derived: it can always
  be rebuilt from the log. Grows by building `code.idx.new` and renaming it over.
- `MANIFEST`: JSON with format version, creation time, source Besu version and code keying.

### Durability and recovery

`put` appends to the log and promises nothing. `sync` forces the log, then writes the index slots,
then advances and forces the index header's `logLength`. The index on disk therefore never points
at log bytes that were not forced first, and `logLength` is a durable watermark.

On open the log is scanned forward from the watermark. Valid records are re-indexed. The first
thing that is not a valid record ends the log; what follows was never acknowledged as synced and is
discarded, with the byte count logged at INFO. Damage below the watermark is corruption: `open`
fails if it can see it (index missing, or log shorter than the index claims), `verify()` finds the
rest. A failed open never modifies the log.

### Reads do not check the CRC

`get` trusts the record; the CRC is checked by the recovery scan and by `verify()`. Decided
2026-09-19. Measured cost of checking, per cold 64 KB record in a sequential sweep (unpinned
laptop): CRC32C 1.7 µs alone, 0.8 µs on top of the 4.6 µs copy to `byte[]`. That is 1-2.5% of a
devnet-8 JUMPDEST-attack block (~57k cold 64 KB code loads), so it is affordable in Stage 1, but it
would force the whole record through memory on every read and so defeat a zero-copy `view` later.
If read-side checking is wanted, verify each record once per process (a bitset with one bit per
index slot) rather than on every read. Revisit when `view` is promoted.
