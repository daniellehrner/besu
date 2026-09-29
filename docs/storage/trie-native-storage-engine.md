# Trie-Native World State Storage Engine

**Status:** Draft / RFC — not approved, not scheduled
**Date:** 2026-09-12
**Scope:** Bonsai world state storage only. Forest is explicitly not supported (§3). Blockchain, trie log and sync segments are discussed but out of scope for phase 1.
**Measurement status:** The §11 keyspace scan, the on-disk usage baseline and a first round of block-time profiling have all been run against mainnet; results in §4.1 and §4.2. Sections revised against measurement are marked **[measured]**; everything else remains analytical.

- **INV-1 holds** for the account trie, by 57 bytes — so an overflow path is mandatory (§5.1).
- **Directories must be sized in bits, not nibbles** — worth 757 GB (§5.1).
- **§5.2's space claim is withdrawn.** World state is a quarter of the node's disk and the saving is 7.6% of it, contingent on fill above 69.3% (§5.2, §8).
- **§5.3 is refuted.** Root computation is 11 ms of a 144 ms block, not the 20–25 ms of keccak alone that was assumed (§5.3).
- **The "one I/O instead of 6–8" premise does not hold on a synced node.** The critical path is CPU-bound; the surviving case is ~26% of block time in on-CPU read-path cost (§4.2, §2.3).
- **The target regime is steady state — a block every 12 s — by decision, not catch-up.** A node spends its life following the chain, so that is what gets measured and optimised (§11.4).
- **The critical path is mostly transactions re-executed after speculative execution failed.** About 46% of transactions are run again sequentially — 36% after a detected conflict, ~10% because their speculative run had not finished — and those re-executions are the bulk of both the EVM and the state-access cost on the critical path (§4.2). Lowering that rate is the first incremental step, and it needs no new storage engine.
- **Recommendation: shelve §5 and run the incremental programme in §11 instead**, revisiting the engine only if that programme stalls on something RocksDB cannot reach.

---

## 1. Summary

Besu stores the Bonsai world state as key-value pairs in RocksDB. Every logical read — "what is the balance of account X" — becomes a sequence of independent random point lookups, and every block commit re-encodes and re-hashes thousands of trie nodes on the JVM heap.

This document proposes replacing the world state backend with a storage engine whose on-disk layout *is* the Merkle Patricia Trie, which computes the state root internally, and which owns block-level versioning directly instead of reconstructing it from trie logs.

The design targets **Amsterdam and later as the primary path**. EIP-7928 Block Access Lists declare a block's entire access set and post-state before execution completes, which turns prefetching from speculation into an exact, one-shot operation and takes root computation off the critical path altogether (§5.6). Pre-Amsterdam blocks remain correct on a fallback path, but are not the optimisation target.

It also argues that this engine should be written in **Java 25**, not a native language.

**What measurement has established so far (§4.1).** The page-fit invariant the whole design rests on holds on mainnet today: every one of 417.9 M accounts fits a single 8 KiB page below a depth-6 directory, with 0 of 16.8 M buckets overflowing — but with 57 bytes to spare on the worst bucket, so an overflow path is mandatory rather than optional. Directories must also be sized in **bits**, not nibbles, which is worth 757 GB and changes §5.1.

**The space case has largely evaporated, and that is the most important finding.** Measured on disk, the world state is 290 GiB of a 1,175 GiB node — 24.7%, against `BLOCKCHAIN`'s 74.6%. RocksDB's compression and key prefix encoding are good enough that a page store's saving on path keys cancels its loss of compression, so the whole prize is the flat copy: **89 GiB, or 7.6% of the node's disk** — and only realised above 69.3% page fill, where the current layout achieves 49.8% and is therefore 1.39× *larger* than what it replaces. §5.2's claim that unification "roughly halves state size" is withdrawn.

**The "one I/O instead of 6–8" framing does not survive profiling either (§4.2).** On a synced node following the chain, the block-import critical path is **CPU-bound, not I/O-bound**: wall clock and CPU time over it are identical to within two samples, and of the 46 ms/block spent on state access only ~9 ms is blocked on disk. The existing preloads have already moved the I/O off the critical path. But the *on-CPU* cost of the read path — the key-value abstraction, the JNI boundary, RocksDB's block-cache lookups and index/filter decode, and the Java cache layers above them — is **~37 ms/block, about 26% of block processing.**

**So the design should be argued as a CPU-efficiency change to the read path, not as an I/O reduction.** That is a better argument and a larger number than the one this document was originally built on, and it is what §2.3 and §2.5 actually claim. §1, §3 and §5.5 have been revised accordingly.

**Where the critical path actually goes (§4.2).** Besu already executes every transaction speculatively in parallel before the sequential pass. On mainnet only about half of those results are usable: 36% of transactions are re-executed after the collision detector reports a conflict and roughly 10% more because their speculative run had not finished when the sequential pass reached them. Those re-executions are 250 of the 299 critical-path samples under block processing, and the synchronous RocksDB reads that make up the 16% "RocksDB row" are reads those re-executed transactions make cold. Reverts account for only 2.1% of transactions, so the conflict rate is the detector's, not the chain's.

**Decision: the regime to optimise is steady state.** A node follows the chain at a block every 12 s for its whole life; catch-up is a transient. Every profile here is therefore the right profile, and the earlier plan to re-run §11.4 under catch-up is dropped.

**Recommendation.** With the space case small, the root-computation case refuted, and the read-path cost dominated by avoidable re-execution rather than by storage, this proposal should be shelved in favour of the incremental programme in §11: cut the re-execution rate, prefetch flat values ahead of execution, tune the RocksDB read path, and only then re-ask whether anything remains that a page engine could reach and RocksDB cannot. The access-pattern trace (§11.3) stays worthwhile in its own right, since the re-execution and prefetch work needs the same data.

---

## 2. Motivation

Five properties of the current implementation, each verified against the tree at the time of writing.

### 2.1 Trie nodes are already keyed by path, not by hash

`BonsaiWorldStateKeyValueStorage:369`:

```java
putAccountStateTrieNode(location, nodeHash, node)
  → composedWorldStateTransaction.put(TRIE_BRANCH_STORAGE, location.toArrayUnsafe(), ...)
```

Storage trie nodes use `accountHash ‖ location`. Bonsai gave up content-addressing some time ago. This is the enabling precondition for everything below: a path-keyed trie can be laid out physically as a trie, a hash-keyed one cannot.

### 2.2 State is stored twice

In `DefaultStateRootCommitter`, the same account value is written to both the flat segment and the account trie:

- `:179` — `sink.putAccountInfoState(addressHash, accountValueBytes)`
- `:150` — `accountTrie.putDeferred(...)` with the same value, which lands inside the leaf node's RLP

and the same for storage slots at `:214` / `:215`.

So the state is materialised twice on disk, and the two copies can diverge. The `MerkleTrieException` → heal path exists to recover from exactly that.

Measured, the duplicated flat copy costs **91 GiB of the 290 GiB of world state on disk** (§4.1). That is the entire prize §5.2 goes after — and §5.2 explains why a page layout that wastes half of every page gives it straight back.

### 2.3 Read amplification is structural

`RocksDBColumnarKeyValueStorage:77` sets `ROCKSDB_BLOCK_SIZE = 32768` and `:234` sets `LZ4_COMPRESSION`. Fetching a ~100-byte account value on a block cache miss reads and decompresses a 32 KB block. A trie descent does this 6–8 times for one logical read, because each node lookup is an independent key.

**Measured, this is the strongest motivation in §2 — but the cost is CPU, not I/O [measured].** State access is 32% of block processing, of which only 8.6% is a `pread` syscall; the rest is 7.4% inside RocksDB in memory (block-cache lookup, index and filter block decode, LRU bookkeeping, memtable probe) and 16.0% in the Java layers above it (§4.2). `SLOAD` alone is 20.5% of the critical path, the largest single identifiable cost in a block. So read amplification is real and expensive — it is simply paid in cycles rather than in disk latency, because the page cache absorbs the reads on a node that is keeping up.

### 2.4 Compaction does no useful work here

The Bonsai flat state is a mutable map of roughly constant cardinality — the same keys overwritten indefinitely. An LSM tree's premise is converting random writes into sequential ones and sorting later; with a stable key set that buys nothing, and the cost is rewriting hundreds of gigabytes repeatedly.

### 2.5 A Java-side cache layer exists to paper over the above

`BonsaiCachedMerkleTrieLoader:45-50` maintains Guava caches of 100,000 account nodes and 200,000 storage nodes, populated by virtual-thread preloads. `FlatDbCacheManager` adds a cross-block account/storage cache on top. These exist because a single logical read is expensive; they cost heap and GC.

**Measured [measured]:** these layers cost **16.0% of the critical path, ~23 ms/block**, purely in on-CPU lookup work that never reaches RocksDB (§4.2) — more than the disk waiting and more than the RocksDB-internal work. They also work: the preloads move 24.4 thread-seconds of storage reads onto scheduler and fork-join threads, which is why the critical path barely blocks. So this is not dead weight to be deleted — it is the mechanism currently *making* Bonsai keep up, and a page-based engine has to beat it rather than merely remove it.

### What is *not* a problem

The parallel state-root work has already landed and should not be re-litigated. `DefaultStateRootCommitter` launches storage-trie updates as `CompletableFuture`s ("launched eagerly so storage I/O overlaps with the sequential account trie staging loop"), collects writes into a `ConcurrentLinkedQueue`, and defers them via `StateRootComputation.applyTo(updater)`. `BlockProcessingExecutors` provides dedicated `accountTrieForkJoinPool()` and `storageTrieForkJoinPool()`, and `ParallelStoredMerklePatriciaTrie` is selected when `isParallelStateRootComputationEnabled()`.

This design builds on that, it does not replace it.

---

## 3. Goals and non-goals

**Goals**

- **Cut the CPU cost of a logical state read**, by replacing 6–8 independent key lookups — each paying JNI, block-cache lookup, index/filter decode and Java cache indirection — with offset arithmetic in one mapped page. Measured target: ~37 ms/block of on-CPU read-path work, 26% of block processing (§4.2). *This was originally stated as "one I/O per logical state read, down from 6–8"; profiling shows the critical path is not I/O-bound on a node keeping up, so the goal is restated in the terms the measurement supports.*
- Exploit Block Access Lists as a first-class input: exact batched prefetch and fully overlapped root computation for Amsterdam+ blocks (§5.6).
- Eliminate the flat/trie duplication and the divergence class of bugs it creates. **Note:** this remains a goal for correctness, but measurement has removed its space justification unless §11.2 succeeds — see §5.2.
- ~~Compute the state root without materialising node encodings on the Java heap.~~ **Demoted by measurement (§5.3):** the whole root computation is 11 ms of a 144 ms block and trie allocation is ~11 MB/block of a 93 MB/block critical path. Worth doing if it falls out of the design; not worth designing for.
- Make block-level versioning (rollback, snapshots, historical reads) a property of the engine.
- Stay in Java so that the storage layer remains reviewable and maintainable by Besu contributors.

**Non-goals**

- Replacing the `BLOCKCHAIN` segment. Headers, bodies, receipts and BALs are append-only blobs with a different shape; they are addressed separately in §8.
- Changing consensus behaviour in any way. The computed state root must be bit-identical.
- **Supporting Forest.** The engine is Bonsai-only by design, not by staging. Its entire layout depends on Bonsai's path-keyed trie nodes (§2.1); Forest is content-addressed and has no path to key on, so there is nothing to extend. Forest is in any case on its way out — Bonsai is the default (`DataStorageOptions:44`, `DataStorageConfiguration:32`), Forest pruning and the `x-backup-state` / `x-restore-state` subcommands have been removed, and Forest test coverage is being retired. No accommodation is made for Forest here and none should be added later.
- Fixing RocksDB's tuning. §2.3 is cheap to test independently and should be (see §11).

---

## 4. Background: the current shape

Segments relevant to Bonsai (`KeyValueSegmentIdentifier`):

| Segment | Key | Value |
|---|---|---|
| `ACCOUNT_INFO_STATE` | 32 B account hash | RLP account, ~70–110 B |
| `ACCOUNT_STORAGE_STORAGE` | 64 B (`accountHash ‖ slotHash`) | RLP UInt256, 1–33 B |
| `CODE_STORAGE` | 32 B | bytecode, up to 24 KB |
| `TRIE_BRANCH_STORAGE` | trie location, 0–64 B, variable | RLP node, ≤ ~532 B |
| `TRIE_LOG_STORAGE` | 32 B block hash | diff blob, KB–MB |

Account and slot keys are keccak outputs, so they are **uniformly distributed**. Nothing in the current layout exploits that.

Concurrency at the storage layer is set by `BlockProcessingExecutors`: a CPU pool of `NCPU`, an IO pool of `NCPU * 2` described as storage prefetch, and separate fork-join pools for account and storage trie hashing.

### 4.1 Measured shape of mainnet state **[measured]**

Produced by `besu storage x-trie-page-fit` (§11), 2026-09-12, at world root `0x36195d51…`, flat DB mode `FULL`. 2,812,611,064 trie keys plus exactly 4 non-trie metadata keys, read in 597 s. With `--verify-hashes`: **0 malformed nodes and 0 hash mismatches** across all 2.8 billion nodes. An earlier run two hours before, at a different world root, reproduced every figure to within 0.004%.

**Methodology note.** Bonsai does not delete storage-trie nodes when a branch collapses or storage is cleared, so summing the segment overcounts. Because trie nodes are path-keyed (§2.1), sorted key order is depth-first pre-order, which lets one sequential pass validate reachability with an ancestor stack; storage roots are additionally checked against the flat account. Unreachable data found: 58 account nodes, 193,583 storage nodes (5.9 MB) and 3 orphaned storage tries. That is small only because this node was freshly synced — a long-running node will hold considerably more, and how much is unmeasured.

**Account trie**

| | |
|---|---|
| accounts | 417,928,785 |
| nodes | 579,671,887 (156.4 M branch, 5.3 M extension, 417.9 M leaf) |
| size | 66.3 GB as stored (RLP), 63.8 GB in the compact model |
| leaf depths | minimum 7 nibbles, mode 8 (291.1 M), maximum 16 |

No account leaf sits shallower than nibble 7, so a directory of depth ≤ 6 always has every account leaf strictly below it.

**Storage tries**

| | |
|---|---|
| contracts with storage | 28,016,618 |
| slots | 1,650,312,982 |
| nodes | 2,232,745,521 |
| size | 156.6 GB RLP, 152.0 GB compact |
| slots per contract | p50 **1**, p90 10, p99 145, p99.9 4,479, max 166,299,426 |
| bytes per contract (compact) | p50 89 B, p90 991 B, p99 13.5 KiB, max 13.2 GiB |

15,112,293 contracts hold exactly one slot — their storage root *is* a leaf. The median contract holds one slot and 89 bytes.

**The skew is extreme, and it is not where the bytes are.** One contract (XEN Crypto) holds 166.3 M slots — **10.1% of every storage slot on mainnet** — in 13.7 GiB. The top 20 contracts hold 24.5% of all slots and 23.8% of all storage bytes. Identifiable in that list: XEN (#1), USDT (#2), USDC (#5), Seaport 1.1 (#6), the Uniswap V3 positions NFT, the ENS registry and base registrar, HEX, WETH, Wyvern Exchange v2, CryptoKitties, Blur and the AAVE token; six were not identified. The composition matters for §5.7: XEN did not exist before October 2022 and is now the single largest consumer of state, so the set is not predictable and must not be special-cased by identity.

**Actual disk usage** — from `besu storage rocksdb usage` on the same node:

| Column family | Keys | On disk |
|---|---|---|
| `BLOCKCHAIN` | 84,049,790 | **876 GiB** (3 GiB SST + 872 GiB blob) |
| `TRIE_BRANCH_STORAGE` | 2,820,678,194 | 199 GiB |
| `ACCOUNT_STORAGE_STORAGE` | 1,650,966,278 | 72 GiB |
| `ACCOUNT_INFO_STATE` | 418,701,065 | 19 GiB |
| `CODE_STORAGE` | 2,812,050 | 8 GiB |
| `TRIE_LOG_STORAGE` | 513 | 80 MiB |
| **total** | 4,977,211,558 | **1,175 GiB** |

Three things follow, and each one matters more than the scan itself.

**The world state is a quarter of the disk.** Trie plus flat segments are **290 GiB — 24.7%** of 1,175 GiB. `BLOCKCHAIN` is **876 GiB, 74.6%**, and 872 GiB of that sits in blob files. This design targets the smaller quarter; §8's "out of scope for phase 1" is deferring the part that occupies three times more disk.

**RocksDB is compressing the trie better than the design can.** The measured trie node values are 207.5 GiB uncompressed, and the segment occupies **199 GiB on disk including all its keys** — an estimated 83 GiB of path keys that a page store would not need to store at all, since position implies the path. So compression plus key prefix delta-encoding (~1.46× on the combined logical size) already pays for the keys and more. A page store saves the keys but forfeits the compression, and those two effects very nearly cancel. My earlier flat-segment estimates were high by 1.7×: accounts cost 48.7 B/key on disk against an estimated 106 B, storage 46.8 B against 76 B.

**The scan is independently corroborated.** RocksDB's own key counts land within 0.3% of the scan's, on all three segments (2,820,678,194 vs 2,812,611,064 trie keys; 418,701,065 vs 417,928,785 accounts; 1,650,966,278 vs 1,650,312,982 slots), with the residual explained by chain growth between the two commands and by `usage` reporting estimates.

Two incidental observations: `TRIE_LOG_STORAGE` holds 513 keys, confirming trie-log retention is at the default 512 blocks, which bounds §11.3's replay window. And `CODE_STORAGE` holds only 2.8 M distinct code hashes against 28 M contracts with storage, so bytecode is already heavily deduplicated and needs no attention from this design.

> ⚠️ Every *other* byte figure in this document is logical and uncompressed. Pages are not compressible without giving up the O(1) offset addressing the design rests on, so page figures compare directly against the on-disk numbers above — see §5.2.

### 4.2 Measured block-time profile **[measured]**

async-profiler 4.0, `asprof -d 300 -t -i 11ms`, three separate runs (`-e wall`, `-e cpu`, `-e alloc --alloc 256k`) on the same node as §4.1: 22 cores, 62 GiB RAM with 45 GiB page cache, NVMe, `-Xmx50g`, in sync and following mainnet.

**Block timing**, from `besu_block_processing_*`:

| | |
|---|---|
| `engine_newPayload` for the head block, 10 blocks sampled | 25, 28, 129, 140, 149, 151, 151, 198, 210, 262 ms — **mean 144 ms** |
| those blocks | 6–42 M gas (60 M limit), 59–331 transactions |
| `state_root_calculation_duration`, n = 339 | p50 7.1 ms, **mean 11.0 ms**, p99 22.4 ms |

So state root computation — everything §5.3 targets — is **7.6% of block processing**.

**Duty cycle.** The critical path is 3.71 s of the 300 s window: a **1.2% duty cycle**, and total CPU was 111.5 core-seconds of 6,600 available — **1.7% utilisation**. The profile independently agrees with the metrics (144 ms × ~25 blocks ≈ 3.6 s).

**Where the critical path goes** (wall clock, 337 samples under `engine_newPayload`):

| phase | share | ≈ ms/block |
|---|---|---|
| pure EVM compute | 40.1% | 58 |
| **state access, under an opcode** | **32.0%** | **46** |
|   → served in Java (accumulator, flat cache, code cache) | 16.0% | 23 |
|   → inside RocksDB but in memory (block cache, index/filter, memtable) | 7.4% | 11 |
|   → blocked in a `pread` syscall | 8.6% | 12 |
| other | 12.2% | 18 |
| persist / commit / trie log | 9.2% | 13 |
| crypto (secp256k1 signature recovery) | 5.6% | 8 |

By opcode, counting frames at any depth so entries nest: **`SLoadOperation` 20.5%**, `AbstractCallOperation` + `CallOperation` 12.7% (the account touch), `AbstractOperation.getAccount` 2.7%, `ExtCodeSizeOperation` 0.6%. `SLOAD` is the largest single identifiable cost in the block, ahead of any arithmetic opcode.

**The critical path does not block.** Wall 337 samples (3.71 s) against CPU 339 samples (3.73 s) — two independent 300 s windows landing within two samples of each other. Of the 46 ms/block of state access, roughly **37 ms is on-CPU and ~9 ms is blocked**. Meanwhile 24.4 thread-seconds of storage reads happen on the `EthScheduler` and `ForkJoinPool` threads: the existing preload machinery has already moved the I/O off the critical path.

**What the critical path is made of [measured].** Besu runs every transaction speculatively on the `besu-block-cpu` pool before the sequential pass, and the sequential pass takes each result if the collision detector accepts it. Under `processBlock`, 250 of 299 wall samples are `processTransaction` — sequential execution — against 13 for taking a speculative result and 23 for persisting. The node's counters over 13 blocks say why:

| outcome of the speculative run | transactions | share |
|---|---|---|
| taken as is | 1,743 | 53.8% |
| re-executed after a detected conflict | 1,163 | 35.9% |
| re-executed because the run had not finished (cancelled) | ~333 | ~10% |
| **total** | **3,239** | |

Reverted transactions — always re-executed, since a reverted frame's reads are never recorded — are only 2.1% of the same window, so the conflict rate is the detector's coarseness, not the chain's. The branch `perf/parallel-tx-outcome-metrics` adds a counter for the cancelled case and labels each conflict by cause; deployed on the node, over 3,394 transactions in 13 blocks:

| cause | transactions | share |
|---|---|---|
| taken as is | 1,974 | 58.2% |
| conflict: account (nonce, balance or code changed earlier) | 975 | 28.7% |
| conflict: storage slot | 310 | 9.1% |
| conflict: mining beneficiary touched | 126 | 3.7% |
| conflict: reverted | 9 | 0.3% |
| speculative run not finished, waited for | 25 | 0.7% |

So the cancelled share was window noise in the first estimate — under 1% — and **the account-level check is three quarters of all conflicts.** Simulating the detector offline on `prestateTracer` output for 10 blocks (2,124 transactions) reproduces these rates and attributes them: of 599 account conflicts, **370 are balance-only changes on a contract the transaction merely sent ETH to** — the Uniswap v4 PoolManager and WETH alone account for 240 — and 226 are the same sender appearing twice in a block, which is a real dependency. A transaction that only credits an account never observes its balance, so the change can be imported as a delta on top of the block's current value instead of as an absolute; simulated, that rule lifts the taken-as-is share from 65.9% to 74.2% of transactions, with the rest of the freed transactions moving to genuine slot conflicts on the same contracts. The slot conflicts themselves hold nothing cheap: 112 of 126 are a read-and-write of a slot an earlier transaction wrote — USDT and USDC balance slots of hot addresses make up over half — and comparing values instead of keys would clear 0 of 126 slot conflicts and 3 of 599 account conflicts. They are real dependencies, and the EIP-7928 access list removes them by construction on Amsterdam, so they are left alone.

The synchronous RocksDB reads are the same story. 45 of the 69 `SLOAD` samples sit in `RocksDB.get`, reached through the flat strategy after missing the accumulator; the preloader is *triggered by* that read and fetches trie nodes for the later root computation, so it never overlaps the value read execution is waiting on. The cross-block flat cache (`--Xbonsai-cross-block-cache-enabled`) is off by default, so every such miss is a RocksDB call.

**The cross-block flat cache removes those cold reads [measured].** With `--Xbonsai-cross-block-cache-enabled` on the same node, a second wall profile over the same 300 s window shows the critical path's RocksDB reads under transactions falling from **17.7% to 3.0%** of block processing and `SLOAD` from 23.1% to 7.4%, with the cache serving 90% of requests. The block-import log's execution time per million gas moved from 4.34 to 4.16 ms over the first 103 blocks (4.03 in the profile window), a 4–7% gain that is within that sample's noise but points the same way as the profile. The cache is off by default, and the Amsterdam `BalPrefetcher` bypasses it by reading the composed storage directly, so what it prefetches lands only in RocksDB's block cache; both are the subject of the first item in "Order of work".

**Sequential execution, for comparison [measured].** With parallel processing disabled and the cache on, block processing is **143 ms/block** against 101 ms with it: EVM 69 vs 64 ms, RocksDB reads 30 vs 3 ms, Java state access 21 vs 8 ms, persist 12 vs 11 ms. The EVM cost is nearly the same although the parallel path re-executes only a third of the transactions (carrying 46% of the gas), so **the scheme's 42 ms gain is almost entirely the speculative pass warming the caches, not the reuse of results.** A prewarm-then-sequential design, which is what reth, Nethermind and ethrex do, would land within ~10 ms of the current scheme. The sequential figure is also the per-block cost the parallel workers carry after Amsterdam: 143 ms of CPU spread over the cores, of which 30 ms are cold reads the BAL prefetcher exists to remove. One hardware caveat: this node is a Core Ultra 7 155H laptop CPU, 16 heterogeneous cores with frequency scaling, so absolute per-gas costs vary with which core the engine thread lands on.

**Allocation.** The JVM allocates **181 MB/s** (54.4 GB over 300 s). Only **4.3% of it is on the critical path** (~93 MB/block), and of that 75.2% is EVM execution against **12.0% — about 11 MB/block — for trie, state root and trie log combined.** By thread, netty event loops (p2p/RPC) are ~35%, JFR itself 8.9%, block import 5.0%. G1 concurrent threads consumed ~19 of the 111.5 core-seconds.

> ⚠️ **Four caveats, and the last one is load-bearing.**
> 1. n = 337 on the critical path. Ordering is reliable; magnitudes carry roughly ±4 points, and sub-buckets of 25–54 samples ±30–40% in relative terms.
> 2. The wall and cpu runs were **sequential, not simultaneous** — different windows, different blocks. Only the aggregate comparison is meaningful; per-phase subtraction is noise.
> 3. JFR was recording throughout (`-XX:StartFlightRecording`), and is itself 8.9% of allocation and 2.8 core-seconds of CPU. It inflated the allocation and GC figures.
> 4. **This is an in-sync node at a 1.2% duty cycle**, where 45 GiB of page cache comfortably holds the working set of ~25 blocks, and the split between the three state-access rows above is cache-dependent. "CPU-bound while following the chain" does not imply "CPU-bound during sync" — but following the chain is how a node runs, and by decision it is the regime this work optimises (§11.4). Catch-up is not measured and is not planned to be.
>
> Separately: the node aborted with `SIGABRT` during the subsequent JFR run, with async-profiler attached and JFR recording concurrently. No `hs_err` survived because `WorkingDirectory` is unset in the service unit, so the JVM tried to write it to `/`. Set `-XX:ErrorFile=` and drop the built-in JFR before profiling again.

---

## 5. Design

### 5.1 Path-sharded extents

Partition the trie by path prefix. A leading prefix of the path forms a **directory** that stays permanently resident. Below it, each subtrie is serialised into a **contiguous page run (extent)** in DFS pre-order, with children referenced by *relative offset* rather than by key. Pointer-chasing becomes offset arithmetic within a mapped region.

The prefix was originally specified as the top *d* **nibbles**. Measurement shows it must be a whole number of **bits** instead, and preferably an arbitrary bucket count — see *Directory granularity* below. *d* is retained in this section where the discussion is about depth rather than width.

An account read becomes:

```
keccak(address) → first d nibbles → direct index into the directory
                → one page read → in-memory walk to the leaf
```

**One I/O, and the full Merkle path comes back with it** — which is exactly what root recomputation and proof serving need anyway.

#### The page-fit invariant

> **INV-1.** The subtrie below the directory, including its internal nodes and its leaf values, must fit in a single page.

This invariant is **load-bearing for the entire design**, and in particular for §5.2. If it does not hold, a read touches several pages — in DFS pre-order the root-to-leaf path is not contiguous, since top branches sit at the start of the extent and the leaf sits deep inside it — and the layout degrades to 2–3 I/Os. That is better than a naive trie walk but **worse than the flat database it is meant to subsume** (§5.2, §4).

#### INV-1 for the account trie: holds, with no margin **[measured]**

Measured bucket sizes in the compact model, 8 KiB pages. "Non-empty buckets" is measured; the directory itself must have 16^*d* entries regardless:

| *d* | non-empty buckets | directory @ 8 B | p50 | p99.9 | max | buckets over | accounts over | fill |
|---|---|---|---|---|---|---|---|---|
| 4 | 65,536 | 512 KB | 952 KiB | 992 KiB | 1,002 KiB | all | all | — |
| 5 | 1,048,576 | 8 MB | 59.5 KiB | 69.0 KiB | 76.5 KiB | all | all | — |
| **6** | **16,777,216** | **134 MB** | **3,807 B** | **6,399 B** | **8,135 B** | **0** | **0** | **46.4%** |
| 7 | 211,853,212 | 2.1 GB | 311 B | 1,039 B | 2,116 B | 0 | 0 | 3.6% |
| 8 | 398,224,617 | 34 GB | 139 B | 447 B | 887 B | 0 | 0 | 1.8% |

**INV-1 holds at *d* = 6 with an 8 KiB page: 0 of 16,777,216 buckets overflow.** Every one of the 16^6 directory entries is populated, so the directory is a dense flat array with no sparse representation needed. The nodes above the directory total 75 MB — comfortably resident, and far cheaper than this section originally assumed.

Three qualifications:

- **There is no headroom.** The largest bucket is **8,135 bytes against an 8,192-byte page** — 57 bytes. The invariant is true today essentially by luck. Either use 16 KiB pages at *d* = 6 (largest bucket at 50% of a page) or accept that an overflow path is required regardless. "Uniform depth with no split path" should be considered refuted even though the invariant currently passes.
- **4 KiB does not work.** At *d* = 6, 34% of buckets and 41.5% of accounts land in over-full buckets. Halving the page to buy fill costs 41.5% of account reads a second page.
- **RLP does not quite make it.** In the as-stored model the largest bucket is 8,425 B and 14 buckets overflow (736 accounts). This is the concrete argument for the compact node format, not merely a size preference.

#### Directory granularity: size it in bits, not nibbles **[measured]**

The original formulation stepped *d* one nibble at a time, which multiplies the bucket count by 16. That is the single most expensive decision in the original design, and it was wrong.

Because keys are keccak outputs, every bucket at a given width is the same size to within a few percent of Poisson noise. **Per-bucket adaptive splitting therefore buys almost nothing — there is nothing to adapt to.** What costs is the granularity of the ladder: the narrowest directory whose buckets fit a page overshoots, and with a ×16 ladder it overshoots by up to 16×. Measured, per-contract storage directories on a nibble ladder ran at **13.7% fill**.

A prefix of whole *bits* still names a contiguous key range, so a bucket still holds a coherent run of subtries and lookup is still a single shift into a flat dense array. Only the ladder changes, from ×16 to ×2:

| storage directories, 8 KiB pages, compact | pages for contracts needing a directory | fill |
|---|---|---|
| nibble-granular (×16 ladder) | 126,218,889 | 13.7% |
| **bit-granular (×2 ladder)** | **33,856,620** | **51.2%** |

A 3.73× reduction. Across the whole layout that is 757 GB (§5.2). Chosen widths spread smoothly over 1–22 bits with no clustering at multiples of four, which is the direct evidence the finer ladder is being used rather than rounding back to nibbles.

**The remaining loss is still quantisation, not variance.** At 51.2% fill, one width narrower puts the average bucket at ~102% of a page — over. So ×2 is itself the binding constraint. The overflow tolerance is not the lever either: only ~15,000 slots of 1.65 billion are estimated to fall past the first page of their bucket, so the 1% tolerance the scan was configured with is essentially unused.

**The next step is to drop the power-of-two constraint entirely.** A directory need not have 2^*k* entries: taking a *k*-bit prefix and computing `bucket = (prefix × B) >> k` for arbitrary *B*, the mapping is still monotone in the prefix — so buckets remain contiguous key ranges holding coherent subtries — and lookup is still one multiply-shift into a flat array. *B* can then be sized to the contract rather than rounded up to the next power of two. A ladder of ~1.2× steps should put fill near 85–90%. This applies to the account directory too, where 2^24 is currently the only available choice anywhere near the right size, and is why *d* = 6 sits at 46.4% fill. Unmeasured; see §11.2.

Two consequences that must be designed for rather than assumed:

- **Growth.** The directory must widen as state grows. Bit granularity dissolves the wall the original design ran into: widening no longer means a 16× jump from a 134 MB directory to a 2.1 GB one, but a 2× step (268 MB) or, with arbitrary *B*, an arbitrarily small one. Linear-hashing-style incremental splitting becomes the natural fit rather than a workaround. Growth measured over a two-hour window was ~62 MB/day of logical trie, but two hours is not a growth rate and a usable figure needs weeks of sampling.
- **Skew.** Bucket population within one directory is Poisson and tightly concentrated, as assumed — the measured *account* spread is p50 3,807 B against an 8,135 B maximum, and that 2.1× max-to-median ratio is what holds fill at 46% rather than the ~71% a ×2 ladder would otherwise give. Across *contracts*, however, storage subtries vary by eight orders of magnitude, from an 89-byte median to 13.2 GiB (§4.1), which is what §5.2 must handle by regime rather than by a single mechanism.

Extent updates are copy-on-write. This yields MVCC snapshots for free (§5.4) and turns "compaction" into bounded, per-extent defragmentation driven by fill factor, rather than a global level rewrite.

**Fanout must be a parameter.** Given the Glamsterdam-era work in this repository, a binary state tree is a live possibility. A design hard-wired to 16-way branching would need rewriting; one parameterised on fanout would not.

### 5.2 Fold the flat value into the leaf — conditional on INV-1

**This section is conditional and must not be read as a decision.** It proposes that the leaf node *is* the flat entry, removing the duplication in §2.2 and eliminating flat-vs-trie divergence as a category.

An earlier draft of this section claimed it would "roughly halve state size". **That claim does not survive measurement** and is withdrawn.

The page store replaces the 290 GiB of trie and flat segments the node actually holds on disk (§4.1). What it would occupy:

| 8 KiB pages, compact, storage ≤1 KiB inlined | pages | size |
|---|---|---|
| account pages | 16,778,700 | 137.5 GB |
| storage that fits a page and is not inlined | 2,297,274 | 18.8 GB |
| storage needing its own directory | 33,856,620 | 277.4 GB |
| **total** | **52,932,594** | **433.6 GB = 403.8 GiB** |
| content held | | 215.7 GB = 200.9 GiB (49.8% fill) |

Against the on-disk baseline rather than a logical one:

| page fill | page store | vs 290 GiB today |
|---|---|---|
| 49.8% (measured, ×2 ladder) | 403.8 GiB | **1.39× larger** |
| **69.3%** | 291.2 GiB | **break-even** |
| 88% (arbitrary-*B*, projected) | 228.3 GiB | 0.79× — saves 62 GiB |
| 100% (unreachable) | 200.9 GiB | 0.69× — saves 89 GiB |

**Three conclusions, and they are less favourable than the logical comparison suggested.**

1. **The page store must exceed 69.3% fill merely to match what it replaces.** The ×2 ladder delivers 49.8%, so as specified the unified layout is 1.39× *larger* on disk. Nibble granularity would have made it 4.2× larger.
2. **The entire prize is the flat copy, and it is 89 GiB.** At perfect fill the page store is about the size of today's compressed trie segment alone (200.9 vs 199 GiB) — because a page store saves the path keys but forfeits compression, and those cancel (§4.1). So the saving available is exactly the 91 GiB of flat segments: 31% of the world state, but **7.6% of the node's 1,175 GiB disk**.
3. **§5.2's space argument is not a property of unification, it is a property of page fill.** Fill work is load-bearing in the same way INV-1 is, and §11.2 is a precondition rather than a follow-up. If arbitrary-*B* lands at 85–90% the saving is ~20% of the world state and under 6% of the disk; if it lands below 70% there is no space saving at all.

The duplication is still worth removing for the divergence-bug argument, which is independent of fill and independent of these numbers. **But this section should no longer be argued on space.**

Unification is also only sound while **INV-1 holds**. The flat database exists to provide a guarantee that is *unconditional*: one hash, one lookup, one I/O, irrespective of state size or key distribution — which is why `BonsaiPartialFlatDbStrategy` treats the flat hit as the fast path and a full `StoredMerklePatriciaTrie` walk as the fallback, with metrics (`get_account_merkle_trie`) counting how often the slow path is taken. Unifying leaf and flat entry replaces that unconditional guarantee with one contingent on page fit. That is a real reduction in robustness and is the central risk of this design, not an incidental detail.

The contingency is now measured rather than unknown: it holds today for every one of 417.9 M accounts, with 57 bytes to spare on the worst bucket (§5.1). That is a quantified risk, not a retired one.

#### Storage needs three regimes, selected by size **[measured]**

Storage keys are `accountHash ‖ location`, so a contract's slots already form a contiguous keyspace. But contract sizes span eight orders of magnitude (§4.1), and the measurement shows a sharp inversion that a single mechanism cannot serve:

> **98.6% of contracts fit entirely in one 8 KiB page, and they hold 6.3% of all slots. The other 1.4% — 392,485 contracts — hold 93.7% of all slots.**

Three regimes follow, all selected by measured size, none by identity:

1. **Inlined into the account's page.** The only regime that is a genuine representation change, because no amount of directory tuning removes a per-contract page. Measured effect on INV-1 at *d* = 6 / 8 KiB:

   | inline threshold | contracts absorbed | bytes | account buckets over | accounts affected |
   |---|---|---|---|---|
   | ≤ 256 B | 19,249,009 (68.7%) | 2.21 GB | 24 of 16.8 M | 0.000% |
   | **≤ 1 KiB** | **25,326,859 (90.4%)** | **5.30 GB** | **1,484 of 16.8 M** | **0.020%** |
   | ≤ 4 KiB | 27,408,755 (97.8%) | 8.72 GB | 102,749 of 16.8 M | 1.503% |

   ≤ 1 KiB is the recommended setting: it absorbs 90% of all contracts for 0.020% of account reads needing a second page. ≤ 4 KiB is too far.

2. **Owns one page.** 2,297,274 contracts after inlining at ≤ 1 KiB. Without inlining this regime is 27.6 M pages holding 10 GB — **4.4% fill**, 226 GB wasted — so inlining is what makes it tolerable, and packing several contracts per page is the alternative if the threshold has to come down.

3. **Owns a directory.** 392,485 contracts, 142.0 GB, directory widths 1–22 bits (§5.1). For these, `SLOAD` costs one page read after the account page.

For regimes 1 and 2, `SLOAD` costs **zero additional I/O** — the design's strongest claim, and it now has a measured denominator: the contracts it applies to are 98.6% of all contracts but hold only 6.3% of all slots. Whether it covers 6% or 90% of the slot reads a block actually issues depends on hotness, which is unmeasured (§11.3) and is the missing half of this argument.

Node hashes are materialised in the node record so that recomputation can skip clean subtrees.

#### If INV-1 cannot be held

**This branch is not taken.** The §11 scan measured INV-1 as holding for the account trie at *d* = 6 with an 8 KiB page (§5.1), and for storage the three regimes above give every contract a page-fit answer. The fallback below is retained because the margin is 57 bytes and the invariant may fail as state grows.

The fallback is to keep flat state and trie as **separate physical representations**: flat state hash-indexed for unconditional single-I/O reads, trie maintained alongside purely for commitment. That is the split-state architecture, and it is what §5.7 rejects for storage-slot locality.

**These are the same decision.** §5.2's unification and §5.7's rejection of raw-slot ordering both rest on INV-1. If the invariant fails, both conclusions flip together, and the design converges on split state with the double-write of §2.2 accepted as a deliberate cost rather than treated as a defect.

### 5.3 Root hash computation inside the engine

Per block, Besu currently hashes on the order of 14,000 trie nodes (~2,000 dirty paths × ~7 nodes). Each one RLP-encodes into heap `Bytes` and hashes through `MessageDigestFactory:57`, which is BouncyCastle's pure-Java `Keccak.Digest256` reached via the JCA `MessageDigest` API.

Moving this into the engine buys:

1. **No heap materialisation.** Nodes are already in mapped memory in the engine's own format; encode to RLP once into a scratch buffer and hash in place. No `Bytes` allocation, no GC pressure.
2. **A tight, allocation-free keccak** rather than JCA dispatch per node.
3. **Structural parallelism at the extent level** without the `synchronized` on `BonsaiWorldStateKeyValueStorage:422`.
4. **Lower CPU cost where it now matters.** On Amsterdam+ blocks root computation is *already* off the critical path — `BalStateRootCommitter.start()` runs it on a background future (§5.6). The target there is therefore not latency but CPU: the background computation competes with execution for cores, and `STATE_ROOT_THREADS` defaults to 1. Making the work cheaper either frees that core or makes one core sufficient where it currently is not. On the pre-Amsterdam fallback path, latency is still on the critical path and the gain is direct.

#### Both of this section's claims are refuted by measurement **[measured]**

The estimates this section was built on were wrong by roughly 5×:

| | estimated | **measured (§4.2)** |
|---|---|---|
| per-block keccak | ~20–25 ms | **~4 ms** (hashing frames on the critical path) |
| whole state-root computation | — | **11.0 ms mean, 7.6% of a 144 ms block** |
| trie/state-root/trie-log allocation | "no `Bytes` allocation, no GC pressure" is the win | **~11 MB/block — 12% of the critical path's 93 MB, and 0.5% of the JVM's 181 MB/s** |

So:

1. **The keccak estimate was ~5× too high.** The entire root computation, including RLP encoding, trie walking and write staging, costs less than this section claimed keccak alone did. Eliminating all of it saves 7.6% of block processing.
2. **"The bulk of the available win comes from removing allocation" is false.** The critical path is 4.3% of the JVM's allocation, and three quarters of *that* is EVM execution. Trie work allocates ~11 MB/block against 181 MB/s JVM-wide, most of it in netty. There is very little trie allocation to remove.
3. **SIMD keccak is therefore worth well under 5 ms**, not roughly 5 ms. It remains irrelevant to §6's decision, now for a stronger reason.

**Consequence: §5.3 should not be built for its own sake.** Items 1–3 above are worth having if they fall out of an engine built for the read path (§2.3, §2.5), and item 4 below may still matter on the BAL path because it concerns core *contention* rather than latency. But the original ordering — which put root computation among the primary motivations — is not supported. §11.5.3 remains worth running as an isolated check, because it is cheap and needs no engine.

> ⚠️ The state root is *the* consensus value. Moving its computation is the single largest risk in this proposal. See §9.

### 5.4 Native versioning

Let the engine own block versioning, and most of the surrounding Java machinery becomes unnecessary:

- **A version is a block.** Retain N versions matching `bonsai-historical-block-limit` (default 512). COW extents make old versions readable at no copy cost.
- **Rollback is a root pointer flip**, not a `TrieLogLayer` replay.
- **Trie logs become derived.** The engine can generate one on demand by diffing two versions, which satisfies the `TrieLogProvider` plugin API. Trie log bloat, the pruner, and `TrieLogSubCommand` largely go away.
- **`LayeredKeyValueStorage`, `PathBasedLayeredWorldStateKeyValueStorage` and `BonsaiSnapshotWorldStateKeyValueStorage` collapse** into "open a read handle at version V". They exist only because a KV interface cannot express versions.
- Reorg becomes `setHead(version)`.

### 5.5 Parallel reads

In impact order:

1. **Cut lookups per logical read from ~7 to 1** (§5.1). Originally stated as I/Os. Measured (§4.2), the saving on a node keeping up is mostly the *per-lookup CPU* — JNI, block-cache lookup, index/filter decode, Java cache indirection — not disk latency, which the page cache already absorbs. An I/O saving would only reappear under catch-up, which is not the regime being optimised (§11.4).
2. **Optimise for queue depth, not thread count.** NVMe delivers ~100k IOPS at QD1 and over 1M at QD128+. A trie descent is a serial dependency chain; the extent layout removes it, leaving cross-key parallelism to exploit.
3. **Batch prefetch.** Replace the current per-key virtual-thread preloads with `prefetch(addressHashes[], slotKeys[])` submitting in one batch. On Amsterdam+ this becomes exact rather than speculative and is the highest-leverage feature in this document — see §5.6. **Measured caveat [measured]:** the existing preloads already work, moving 24.4 thread-seconds of storage reads off a 3.7 s critical path (§4.2, §2.5). The gain here is making prefetch exact and cheaper, not making it exist.
4. **Lock-free readers.** COW versioning means readers hold a version pointer and touch immutable extents; no reader-writer contention at all. Reclaim via epoch-based reclamation.
5. **No verification on the read path.** Node hash checks are already assert-only (`StoredNodeFactory:113`). Per-page CRC32C via `java.util.zip.CRC32C` is intrinsified to the hardware instruction and effectively free.

### 5.6 Block Access Lists: the Amsterdam fast path

**This is the primary target of the design.** Everything in §5.1–§5.5 is worth more on a block that carries a BAL than on one that does not.

#### What the BAL gives the engine

`BlockAccessList.AccountChanges` carries, per touched address: `storageChanges` (post-value per transaction index), **`storageReads`**, `balanceChanges`, `nonceChanges` and `codeChanges`. Two consequences matter here:

1. **The complete access set is declared** — reads as well as writes — before execution runs.
2. **The complete post-state is derivable from the BAL alone.** `BlockAccessListChanges.latestChanges()` folds the per-transaction entries into final values. This is why `BalStateRootCommitter.start()` can compute the root on a background future with no input from execution, and why that root is treated as authoritative (a mismatch against the header throws).

The change set this document previously proposed handing to the engine in a single call already exists, is already serialised, and already arrives with the block.

#### Exact prefetch in physical order

This is the combination that pays. The BAL yields the exact set of accounts and slots the block will touch. §5.1 maps each of those to a deterministic extent identifier — computable from the key alone, without reading anything. So at block arrival the engine can:

1. map the whole access set to extent IDs,
2. deduplicate (a contract's slots collapse to a handful of extents; §5.2 inlining collapses many to one),
3. sort into physical on-disk order,
4. issue the result as one batched submission.

**The block's random read set becomes a near-sequential scan, issued at high queue depth, before the first transaction executes.** Under the current layout this is not possible: without the extent mapping there is no way to turn a set of keys into an ordered I/O plan, and prefetch degrades into thousands of independent random lookups — which is what the per-key virtual-thread preloads in `BonsaiCachedMerkleTrieLoader` do today.

#### Fully overlapped root computation

With the post-state known upfront, the engine builds the new trie from the BAL and returns the root concurrently with execution. Execution validates rather than produces it. `BalStateRootCommitter` already establishes this control flow; the engine changes what happens underneath it, not the shape.

Combined, an Amsterdam+ block ideally costs: one batched prefetch at arrival, execution against warm state, and a root that is already computed by the time it is needed.

#### Pre-Amsterdam fallback

Blocks without a BAL must remain correct, and the existing gate is the right one — `StateRootCommitterFactory.resolveMode` already falls back to `DefaultStateRootCommitter` when `maybeBal` is empty. On that path:

- prefetch is **speculative**, driven by the current preload heuristics rather than a declared set;
- root computation stays **on the critical path**, following execution.

The engine must support this path but should not be shaped around it. §5.1–§5.4 still apply and still help — one I/O per read, no double-write, cheaper hashing — but the §5.6 wins are unavailable by construction, because the information simply is not there.

> Design rule: no feature should require a BAL to be *correct*, and no pre-Amsterdam constraint should be allowed to compromise the BAL path.

### 5.7 Alternative considered: ordering storage by raw slot number

Solidity lays out scalar state variables at sequential slot numbers, and dynamic arrays at `keccak(slot) + i`. Contracts frequently read these together. Because the trie keys storage by `keccak(slot)`, sequentially-numbered slots land at unrelated positions — so ordering flat storage by *raw* slot number instead would cluster them. This is the split-state approach taken by some other clients.

**Feasibility.** `BlockAccessListDecoder:55` and `:73` construct `new StorageSlotKey(readUInt256Scalar())`, which retains the pre-image, so on Amsterdam+ raw slot numbers are available for the entire access set at no cost. Off the BAL path `StorageSlotKey.getSlotKey()` is frequently empty — reconstructed from a hash there is no pre-image — so the ordering would be derivable only where BALs exist.

**Rejected for this design, for three reasons.**

1. **Most of the win is already captured.** Keys are `accountHash ‖ slotHash`, so one contract's slots are contiguous regardless of intra-contract ordering, and §5.2 inlines small contracts into a single extent. The gain applies only to contracts spanning multiple extents.
2. **Mappings have no sequential structure.** `balanceOf[addr]` sits at `keccak(addr ‖ slot)` — already uniformly random *as a slot number*. Mappings are the bulk of large-contract storage, and raw-slot ordering does not cluster them at all. The benefit narrows to scalar head slots and array iteration.
3. **BALs remove the part that hurts.** Scattered slots cost most when read as a *dependent* serial chain. §5.6 issues the whole declared access set as one batched submission at high queue depth, so scatter costs I/O *count*, not latency. Locality reduces count; batching removes serialisation, and serialisation is the expensive half.

**Costs that decided it.** Raw-slot ordering forces flat state into a different physical order than the trie, which means two physical representations (reinstating §2.2), root computation no longer sharing faulted-in pages with execution, and a second index for hash-ordered snap-sync range serving. Under BAL, root computation runs concurrently with execution, so page sharing between them matters *more*, which strengthens the case against splitting.

**What would overturn this.** Two things, both measurable: INV-1 failing (§5.2), which forces split state anyway and makes the ordering question free to revisit; or evidence that reads of sequential scalar/array slots in multi-extent contracts are a material fraction of storage access.

**Status after measurement.** The first condition is settled — INV-1 holds (§5.1), so this rejection stands on its own reasoning rather than by default. The second remains unmeasured; §11.3 is the experiment that would settle it, and until then the reasoning above is analytical, not empirical.

**Where this decision does not apply.** RPC paths — `eth_call`, `eth_getStorageAt`, tracing — have no access list and still issue dependent serial reads, so reason 3 does not hold there. This is not the block-import critical path, but it is the case where locality would genuinely help.

### 5.8 Alternative considered: a separate regime for the largest contracts **[measured]**

The skew in §4.1 invites a tier for the handful of enormous contracts. Under the original nibble-granular design the case looked overwhelming: 436 contracts needing a directory of depth ≥ 4 consumed **56% of all storage pages**, and XEN alone consumed 12.8%.

**Rejected as an identity-based tier. Accepted as a size-adaptive mechanism** — which §5.2's three regimes already are.

**Why not by identity.** XEN did not exist before October 2022 and is now the largest single consumer of mainnet state by a factor of six. Six of the current top twenty could not be identified at all. A hardcoded list rots, and it makes physical layout a client-specific behaviour with no consensus check to catch divergence.

**Why a tier is not needed anyway.** The whales were never intrinsically expensive; they were the worst victims of the ×16 quantisation (§5.1). For XEN at 8 KiB pages, holding 13.2 GiB in the compact model:

| | directory | pages | pages occupy | fill |
|---|---|---|---|---|
| nibble ladder | depth 6 | 16,777,216 | 137 GB | 10.3% |
| bit ladder | 22 bits | 4,194,304 | 34 GB | 41.3% |

Fixing granularity made the largest contract on mainnet cost roughly what its size warrants, through the same code path as a three-slot contract. There is nothing left for a whale tier to fix.

**The axis that would justify differentiation is hotness, and it is anti-correlated with size.** XEN, HEX and CryptoKitties are enormous and effectively cold archives; USDT, USDC, Seaport, Blur and the Uniswap positions NFT are comparatively small and very hot. A size-based tier would place the coldest 14 GB on the same footing as the hottest 2 GB. If per-contract differentiation is wanted for page placement, caching or prefetch priority, the signal is access frequency — §11.3 — not size.

**Any size threshold is adversarially reachable.** A contract sitting just under the inline threshold is cheap to create in bulk, and many of them in one account bucket push it over a page — which is exactly what the ≤ 4 KiB row of §5.2 shows. So every threshold needs an overflow path regardless, and one-way promotion (once a contract owns a page it keeps it) to stop a contract oscillating across the boundary from rewriting pages every block.

---

## 6. Language and runtime: Java 25

**Recommendation: implement in Java 25, in-tree.**

The toolchain is already pinned to 25 (`gradle/gradle-daemon-jvm.properties`) and `--enable-native-access=ALL-UNNAMED` is already set (`build.gradle:940`).

### What Java 25 provides

- **`FileChannel.map(mode, offset, size, Arena)` returns a `MemorySegment`**, not a `MappedByteBuffer`. This lifts the 2 GB limit that historically forced JNI for large mappings. A multi-hundred-gigabyte extent file maps as one segment, shared across threads via `Arena.ofShared()`.
- **Everything off-heap.** Extents, buffer pool, resident directory. The heap stays small and GC becomes largely irrelevant to the storage layer.
- `MemorySegment` accessors are C2 intrinsics; bounds checks are typically hoisted and are noise against cache-miss-dominated access.
- `java.util.zip.CRC32C` is intrinsified.
- `madvise`, `mmap` with huge pages, `sched_setaffinity` and `O_DIRECT` are FFM downcalls — setup-time, not hot path.

### Where Java loses, and by how much

- **SIMD keccak** requires `jdk.incubator.vector`, still an incubator module in 25, requiring `--add-modules` and subject to change between releases. For a consensus-critical path in a production client that is a poor trade for what §5.3 now measures at **under 4 ms/block of keccak in total**, of which vectorisation could recover a fraction.
- **io_uring** has no JDK support. It is implementable over FFM — map the submission and completion rings as `MemorySegment`s, write SQEs as memory stores, and use `IORING_SETUP_SQPOLL` so the submit path needs no syscall. Feasible, but it is work that a native implementation could avoid by using an existing library.

### Why this is the right trade

The largest structural win identified in earlier analysis was collapsing thousands of per-key calls per block into a single batched hand-off. **In Java that cost is zero, not one** — there is no boundary to cross. The accumulator feeds the engine directly.

Everything that actually moves the needle — the extent layout, removing the double-write, batch prefetch, lock-free COW versioning — is layout and algorithm, and is language-independent.

Against that, a native engine makes storage bugs fixable by a small subset of contributors, behind a cross-compilation toolchain for four platforms, with ABI and panic-safety concerns, and replaces one opaque native blob with another. That is a steep price for a fraction of 4 ms of keccak — and measurement has made the price steeper, since the read-path CPU cost that now carries the design (§4.2) is layout and indirection, which Java addresses as well as any language.

**One escape hatch stays open:** batched keccak, shipped through the existing `besu-native` path (`platform/build.gradle:172` already depends on `besu-native-common`). It is stateless, easily fuzzed, and the safest possible thing to put behind FFM — the opposite of putting the whole engine there. Add it only if measurement justifies it.

---

## 7. Integration with Besu

The refactoring that introduced `ethereum/core/plugin-api-worldstate-backend` created the right seam. That module defines `MutableWorldState`, `WorldStateKeyValueStorage`, `StateRootCommitter` and `StateRootComputation`, and `StateRootCommitterFactory` already selects between `Default`, `Bal`, `Forest` and `TrieDisabled` implementations.

A trie-native engine plugs in as:

- a **`StateRootCommitter`** implementation, replacing `DefaultStateRootCommitter`'s trie walk with a single hand-off of the accumulator's change set followed by `root()`;
- a **`WorldStateKeyValueStorage`** implementation backing reads;
- **no changes to `AbstractBlockProcessor` or the accumulator.**

This is a significantly less invasive integration than would have been possible before that refactor, and it means the engine can be developed and benchmarked behind a config flag alongside the existing path.

---

## 8. Out of scope for phase 1

The `BLOCKCHAIN` and `TRIE_LOG_STORAGE` segments are append-only blobs with time-ordered keys and prefix-range deletion. They belong in a segmented append-only log where pruning is a segment unlink rather than blob garbage collection. That is a separate, simpler, and independently valuable piece of work — and a good place to build and validate the engine's infrastructure against low-risk data before touching world state.

**Measurement has made this the larger prize, not the smaller one [measured].** `BLOCKCHAIN` occupies **876 GiB, 74.6% of the node's 1,175 GiB**, with 872 GiB of it in RocksDB blob files (§4.1). The world state this entire document is about is 290 GiB, and the best case §5.2 can offer is a 89 GiB saving — 7.6% of the disk. If the objective is disk footprint rather than read latency, the `BLOCKCHAIN` segment is where three quarters of the bytes are, and pre-merge pruning (`PrunePreMergeBlockDataSubCommand`) is already a shipped lever against it. That does not invalidate the case for this design, which is about I/O per read, but it does mean **this document should not be sold on space**, and the phase-1 ordering deserves revisiting.

---

## 9. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **The premise may not hold: the critical path is CPU-bound, not I/O-bound** | **Highest** | Measured (§4.2): on a synced node, wall and CPU time over `engine_newPayload` are identical, and only ~9 ms/block of 144 ms is blocked on disk. The design's remaining case is the ~37 ms/block of on-CPU read-path work, which is real but is a different argument from the one this document was written on. Steady state is the regime that matters, by decision (§11.4), and there the premise does not hold. The on-CPU read-path cost is in turn mostly reads made by transactions re-executed after failed speculation (§4.2), which is addressable without a new engine. **This risk has materialised; see the recommendation in §1.** |
| State root computation is consensus-critical | **High** | Differential fuzzing against the Java trie, permanently. Reference tests as a hard gate. Run both implementations in parallel and compare, behind a flag, before switching. |
| **INV-1 stops holding as state grows**, so §5.2 degrades to 2–3 I/Os and is worse than the flat database it replaces | **High** | Validated by keyspace scan (§4.1, §5.1): holds today at *d* = 6 / 8 KiB for all 417.9 M accounts — but by **57 bytes** on the worst bucket. Either move to 16 KiB pages, or ship the overflow path before the invariant is relied on. Re-run the scan each release. Monitor overflow-page hit rate in production as a first-class metric. |
| **Page fill makes the unified layout larger than what it replaces** | **High** | Measured against actual disk (§4.1, §5.2): the page store needs **>69.3% fill to break even** and currently achieves 49.8%, making it **1.39× larger** than today's 290 GiB. §11.2 must land above 70% before §5.2 can be argued on space at all. |
| **Uncompressed pages forfeit RocksDB's compression** | **Resolved — and it cost most of the upside** | Measured (§4.1): RocksDB holds 207.5 GiB of trie node values plus ~83 GiB of path keys in 199 GiB on disk. A page store drops the keys but forfeits compression, and the two effects cancel — so the entire available saving is the 89 GiB flat copy, 7.6% of the node's disk. No longer a risk, but it caps the benefit. |
| Bugs in a bespoke on-disk format cause unrecoverable corruption | High | Per-page CRC32C; a format version in the header; a verified rebuild-from-scratch path; no in-place migration (§10). |
| Extent fragmentation degrades over time | Medium | Background per-extent defrag. Bounded and local, but must be measured over a long-running node, not a short benchmark. |
| COW write amplification under sync | **High** (raised) | Batch co-located updates so one extent rewrite absorbs many changes. A block writing a few thousand slots and accounts into distinct 8 KiB pages dirties ~40 MB, against ~500 KB of flat writes today — roughly 80×, or 288 GB/day. That collapses only if the same pages are dirtied block after block, and page-level temporal reuse is **unmeasured**. This is the measurement most likely to disqualify the design; §11.3 exists to settle it. Snap sync needs explicit batching. |
| Scope. This is a large piece of work | **High** | Phase it (§11). Phase 0 is measurement and may conclude the work is not justified. |

---

## 10. Open questions

1. **Does the measurement support the premise?** **Answered for one regime, and the answer is no.** INV-1 holds, so the layout is feasible (§4.1, §5.1) — but on a synced node descent I/O *is* a small fraction of block time: ~9 ms of 144 ms, with the critical path CPU-bound (§4.2). By the criterion this question set, the proposal is not justified **as originally framed**. It retains a narrower case — 26% of block processing in on-CPU read-path work — and an untested one in the sync regime. The earlier restatement — does the premise hold during sync and catch-up? — is withdrawn: steady state is the target regime (§11.4), and there the answer is no. **Closed.** The proposal is shelved in favour of §11's incremental programme.
2. **Migration.** There is no in-place conversion path. Resync, or an offline export/import tool? The import tool doubles as the bulk-load path and the benchmark data source, which argues for building it early.
3. **Snap sync serving.** Ordered iteration by account hash is a DFS over the trie, i.e. a sequential extent scan, and range proofs need trie nodes the engine holds natively — this should get *better*, but it needs designing rather than assuming.
4. **Archive mode.** `ACCOUNT_INFO_STATE_ARCHIVE` / `ACCOUNT_STORAGE_ARCHIVE` use block-number-suffixed keys and `getNearestBefore`. Native versioning may subsume this entirely, or may not. Unresolved.
5. **Prior art.** Several other clients have built trie-native or page-based state stores. Their failure modes are documented and worth reading before writing code. Not cited here pending agreement on referencing external projects in this repository.
6. **Benchmarking the BAL path before Amsterdam is live.** §5.6 is the primary target but mainnet does not yet produce BALs, so the phase 0 measurement has no production workload for it. Devnet, or replaying mainnet blocks with synthesised BALs, are the options. Note also that the BAL root path is behind `BalConfiguration.isBalStateRootEnabled()`, so any measurement must state whether it was on.
7. **BAL trust boundary.** The BAL-computed root is authoritative and a mismatch throws. An engine that consumes the BAL directly for prefetch is only taking a performance hint and is safe. An engine that consumes it to *build* state inherits that trust assumption — worth being explicit about which of the two is being done at each point.
8. ~~**What is the current on-disk size, compressed?**~~ **Answered (§4.1).** World state is 290 GiB of a 1,175 GiB node. RocksDB's compression plus key prefix encoding is good enough that a page store's saving on keys cancels its loss of compression, which caps §5.2's benefit at the 89 GiB flat copy and sets a 69.3% break-even fill (§5.2).
9. **How much dead trie data does a long-running node carry?** Bonsai never deletes storage-trie nodes on branch collapse or storage clear (§4.1). A freshly synced node held 193,583 unreachable storage nodes; a node that has been following the chain for a year is unmeasured and could be much larger. This affects both the space comparison and whether the new engine needs its own reclamation path or inherits the same leak.
10. ~~**Will it fit a 2 TB node?**~~ **Answered, with a caveat.** The measured node is **1,175 GiB today — 59% of a 2 TB drive**. Replacing the 290 GiB of state with the page store as currently specified takes it to **1,289 GiB (65%)**; at the projected 88% fill, to **1,113 GiB (56%)**. So the design neither rescues nor endangers a 2 TB node — it moves the total by ±6 percentage points, while `BLOCKCHAIN` sets the headroom (§8). The caveat is growth: the only figure available is ~62 MB/day of logical trie from a two-hour window (§5.1), which is not a growth rate. A node near the edge of 2 TB is there because of block and receipt history, not state.

---

## 11. Phase 0: measure first

**Nothing in §5 should be built before this is done — and on what is done, §5 is shelved.** §11.1, §11.4 and §11.5.1 are done and their result is the incremental programme in "Order of work" at the end of this section. §11.2 and §11.3 remain useful, §11.3 to that programme and §11.2 only if §5 is reopened.

### 11.1 INV-1 keyspace scan — done **[measured]**

Implemented as `besu storage x-trie-page-fit` (`app/src/main/java/org/hyperledger/besu/cli/subcommands/storage/`, with the analysis in the `pagefit` package). Read-only, opens the Bonsai column families directly, stop the node first. Sweeps account directory depths and per-contract storage directory widths against a set of page sizes, in two size models — RLP as stored, and the compact format §5.2 assumes — and optionally checks every node against its parent's hash reference. Run twice against mainnet on 2026-09-12; results in §4.1, §5.1, §5.2 and §5.8.

The original framing offered two outcomes. The measurement produced a third:

- ~~INV-1 holds with margin → the design is as written~~
- ~~INV-1 is marginal or fails → split state~~
- **INV-1 holds without margin** → the design proceeds as written *and* an overflow path becomes mandatory rather than optional, because the worst account bucket is 57 bytes inside an 8 KiB page.

Three things the scan changed that no amount of analysis had surfaced: directories must be sized in bits rather than nibbles (§5.1, worth 757 GB); §5.2's "roughly halves state size" claim is false as written and depends on fill work (§5.2); and a separate regime for the largest contracts is unnecessary once granularity is fixed (§5.8).

### 11.2 Arbitrary-*B* directory sizing — next, and load-bearing

**This is no longer an optimisation.** §5.2's space argument depends entirely on page fill, which currently sits at 49.8%. Sweep a ladder of ~1.2× steps in bucket count instead of the power-of-two ladder, using the monotone `(prefix × B) >> width` mapping in place of a right shift. Same single-pass scan, roughly ten minutes.

- If fill reaches **85–90%**, the page store is 223–236 GiB against the 290 GiB on disk today — a 19–23% saving on world state, under 6% of the node's disk (§5.2).
- If fill stays below **69.3%**, there is no space saving at all and §5.2 must be argued on divergence alone.

Either way the outcome is modest, which is why §11.4 rather than §11.2 now carries the justification for the design.

### 11.3 Access patterns: the missing half

Two claims in this document have no empirical support and both need the same trace:

- §5.2's "`SLOAD` costs zero additional I/O for 98.6% of contracts" has no denominator. Those contracts hold 6.3% of slots *by count*; the share of actual accesses they serve is unknown and could be anywhere.
- §9's COW write-amplification row is the likeliest single disqualifier for the whole design, and it turns entirely on whether the same pages are dirtied block after block.

**What to compute**, in priority order:

1. **Temporal reuse of written pages** — distinct pages dirtied per block, and what share were also dirty in the previous block and the previous ten. Settles write amplification.
2. **Miss-ratio curve** — reuse-distance histogram over the page-read trace, which yields the hit rate for every cache size in one pass. Settles how much memory this design needs to beat Bonsai.
3. **Intra-block clustering** — distinct pages touched ÷ distinct items accessed. Expected ≈ 1, because keccak destroys locality; if it really is 1, page-granular reads buy nothing on a cache miss and the design rests wholly on residency.
4. **Hotness against size** — per-contract access counts joined to the per-contract sizes from §11.1. Settles §5.8.

**How to collect it.** `AbstractBlockProcessor.getBlockImportTracer` resolves a plugin-registered `BlockImportTracerProvider` (`ethereum/core/plugin-api-execution/.../BlockImportTracerProvider.java`) on every block the node processes, live sync included. A plugin registering one needs no core change. Inside the tracer, take the access set from the EVM's own warm/cold table — `frame.getWarmedUpStorage()`, the trick `AccessListOperationTracer` uses — which yields touched addresses and slots without matching opcodes; collect writes separately by matching `SSTORE` and reading `frame.getMaybeUpdatedStorage()`, as `DebugOperationTracer.captureStorage` does.

The EIP-7928 BAL machinery would be cleaner — `AccessLocationTracker` already classifies reads against writes properly — but `BlockAccessListFactory` is wired only into the Amsterdam spec (`MainnetProtocolSpecs.java:1268`) and `AbstractBlockProcessor` holds it as an empty `Optional` pre-Amsterdam, so nothing is collected on today's mainnet. Force-enabling it means enabling construction *without* validation, since mainnet blocks carry no BAL to validate against.

**Backward replay is bounded, and the bound is time-sensitive.** `PathBasedWorldStateProvider.getFullWorldStateFromCache` refuses any block more than `--bonsai-historical-block-limit` behind the head (default **512**, ~1.7 hours), whether reached through `debug_traceBlockByNumber` or the plugin `TraceService.trace(from, to, …)` API. That is enough for metrics 1, 3 and 4 but nowhere near the ~50,000 blocks metric 2 needs. **Raise the limit on the measurement node now** — pruned trie logs cannot be recovered, so the window only grows forward from whenever it is set. `blocks import` is no help: it skips any block already in the chain and has no tracer option.

**Mapping accesses to pages** reuses §11.1 directly: an account access maps to the top 24 bits of its hash, a slot access to its contract plus the top *k* bits of the slot hash where *k* is that contract's measured directory width. Emit the trace as hashed keys truncated to 8 bytes per component — lossless for page mapping, since widths never exceed 28 bits — so the analysis joins against the scan output without rehashing.

**Three ways to get this wrong.** Counting opcodes rather than state accesses, since a warm `SLOAD` never reaches storage — record a warm/cold flag and decide at analysis time rather than baking one caching model into the collector. Sampling a single day, when whatever airdrop or mint is running that week will dominate the skew. And letting arbitrage bots set the design: they touch the same few pools every block, which produces a flattering reuse curve for a workload that may not persist. Report the curve and its variance, not one headline number.

### 11.4 Block-time profiling — done, for the regime that matters **[measured]**

Run with async-profiler on 2026-09-12; full results in §4.2. Method that worked: `asprof -d 300 -t -i 11ms -o collapsed` for `-e wall`, `-e cpu` and `-e alloc`, with `-t` putting thread names in as root frames so the critical path can be filtered afterwards. Wall mode samples every thread equally regardless of state, so of 8.63 M samples only 337 were on the block-import path — the filtering is essential and the sample is thin.

**What it settled.** The critical path is CPU-bound (wall 337 samples vs CPU 339); `SLOAD` is the single largest cost at 20.5%; state access totals 32% but only 8.6% is a `pread`; the existing preloads already move I/O off the critical path; and §5.3's estimates were ~5× too high on time and wrong in kind on allocation.

**What it settled on a second look.** Tracing every state-access sample to its deepest frame shows the critical path is mostly transactions re-executed after failed speculation, and the synchronous RocksDB reads are those re-executions reading cold (§4.2). That is the finding this section was for: the bottleneck is not storage, it is how much of the block the sequential pass has to run itself.

**Decision on regime.** An earlier draft called for repeating this under catch-up. That is dropped: a node runs at a block every 12 s for its whole life, catch-up is a transient, and optimising for it would optimise the wrong thing. Steady state is the regime, and the profile above is the right profile — thin, at 337 samples, but right.

**Measuring steady state properly.** At a 1.2% duty cycle, 300 s yields ~340 critical-path samples, too few to A/B a change. Profile for an hour with `-t` to get several thousand, keep the block-processing metrics over 24-hour windows as the A/B signal, and do not restart the node right before a run — a cold page cache is not steady state. Practical notes from the first run: drop `-XX:StartFlightRecording` while async-profiler is attached (the node aborted with `SIGABRT` with both active, §4.2); set `-XX:ErrorFile=/var/lib/besu/hs_err_pid%p.log`, since `WorkingDirectory` is unset and the crash log was lost; and consider `-Xmx16g` rather than `-Xmx50g` on a 62 GiB box so page-cache behaviour is stable.

The original plan for this section follows, for the record; the split it asks for has been superseded by the outcome split in §4.2.

Instrument the `StateRootCommitter` path and split block wall-clock into:

- trie descent I/O (waiting on storage)
- RLP encoding
- keccak
- deferred write application and commit

**Measure BAL and non-BAL blocks separately.** The two have different critical paths and averaging them hides the answer:

- **Non-BAL blocks** — root computation is on the critical path, so the split above reads directly.
- **BAL blocks** — root computation is already overlapped, so the number that matters is different: how long execution *stalls waiting on storage reads*, and whether the background root computation is finishing before it is needed or becoming the block's tail latency on its single default thread.

That measurement decides the build order, and may decide whether to build at all:

- If **execution stalls on storage reads** (expected to dominate on BAL blocks), §5.1 plus §5.6 prefetch is the priority and §5.3 is secondary.
- If **descent I/O dominates** on non-BAL blocks, §5.1 is the priority. — *Not the case while following the chain: ~9 ms of 144 ms (§4.2), and steady state is the regime (§11.4).*
- ~~If **keccak and encoding dominate**, §5.3 is the priority~~ — *settled: they do not, 11 ms of 144 ms (§5.3).*
- If **the background root computation is the tail** on BAL blocks, §5.3 matters more than its latency table suggests, because it is competing for a single core. — *Still open; mainnet has no BAL path, so this needs a devnet (§10.6).*
- If **none of these dominate**, the bottleneck is elsewhere and this proposal should be shelved. — *This is the outcome. The bottleneck is re-executed transactions (§4.2): execution is 40% of the critical path, and the state access under it is mostly the cold reads those re-executions make. Neither is addressable by a storage change; both are addressable in the parallel processor.*

### 11.5 Three further cheap experiments

1. ~~Run `besu storage rocksdb usage`~~ — **done**, results in §4.1. It changed §5.2's conclusion more than the keyspace scan did.
2. Reduce `ROCKSDB_BLOCK_SIZE` to 4096 and disable compression on `ACCOUNT_INFO_STATE` and `ACCOUNT_STORAGE_STORAGE`. If block import improves materially, §2.3 is confirmed and quantified — and this may be worth shipping on its own merits regardless of this proposal.
3. Replace the JCA `MessageDigest` path for trie node hashing with a direct allocation-free keccak. Still worth doing because it is cheap and independent, but §5.3 now caps the prize at a fraction of 4 ms/block — treat it as a tidy-up, not an experiment that can change a decision.

### Order of work

§11.1, §11.5.1 and §11.4 are done. The programme is now incremental work on the existing RocksDB-backed Bonsai, ordered by measured size of the prize. §5 is shelved and is not on this list.

1. **Cut the re-execution rate of parallel block processing.** 46% of transactions run sequentially on the critical path (§4.2). In order: (a) land the outcome counters (`perf/parallel-tx-outcome-metrics`, done) and read the cause split off the node; (b) wait for an unfinished speculative future instead of cancelling it and executing the same transaction again (done, same branch: cancelling never stopped the worker, so the transaction ran twice; the wait is bounded to a second) — worth up to the ~10% cancelled share, to be confirmed by (a); (c) import a balance change as a delta when the speculative run only credited the account and never observed its balance, and stop treating that as a conflict — sized by simulation at +9 points of transactions taken from the speculative run (§4.2); *implemented on branch `perf/delta-balance-import`: a per-opcode tracer records balance observations (BALANCE, SELFBALANCE, value-carrying CALL/CALLCODE/CREATE/CREATE2/PAY, SELFDESTRUCT, the sender), and an account seen empty by either side never qualifies because emptiness is observable through call costs and state clearing; **measured on the node**, cache on in both periods: transactions taken from the speculative run 60.4% → 67.1% (134 and 187 blocks), but execution time per million gas 4.00 → 4.39 ms and gas-bucket medians mixed — no timing gain resolvable against hour-to-hour variance. A sequential-execution profile explains why (§4.2): the scheme's gain is nearly all cache warming by the speculative pass, and result reuse itself saves only ~5 ms of EVM time, so fewer conflicts buy little;* the same-sender conflicts that remain (10% of transactions) need dependency-ordered execution, a larger change. Every percentage point of re-execution removed takes both its EVM time and its cold reads off the critical path.
2. **Prefetch flat values ahead of execution.** *Cross-block cache measured: it removes the critical path's cold reads (§4.2). Next: route `BalPrefetcher` through the cache manager and make the cache default-on.* The `SLOAD` read that misses the accumulator is synchronous and cold, and the preloader it triggers fetches trie nodes, not the value (§4.2). Pre-Amsterdam the sources are transaction access lists, senders, recipients and their code, issued as one batch through the existing multi-get cache path before the first transaction runs; the `BalPrefetcher` already does the exact version for Amsterdam. Cheap experiment first: turn on `--Xbonsai-cross-block-cache-enabled` on the node, which makes the speculative pass's reads serve the sequential pass's, and measure.
3. **RocksDB read-path configuration** — §11.5.2. Block size 32 KiB to 4 KiB and compression off on the two flat column families, index and filter blocks pinned. Attacks the cost of the reads that stay synchronous after 1 and 2.
4. **§11.3 access patterns.** Still worth collecting: the same trace answers how much of the cold-read set the sources in step 2 cover. Raise `--bonsai-historical-block-limit` regardless; the window only grows forward.
5. **Java-side micro-costs — implemented on branch `perf/state-layer`.** The sequential profile put Java-side state access at 21 ms/block: 5 ms starting a virtual thread per trie preload from the critical path, ~3.5 ms in the sorted EIP-2929 warm tables (a chain of 32-byte comparisons per warm-up), ~2 ms in the sorted per-account updated-storage map walked through the nested updaters, the rest cache and accumulator lookups. Three commits: preloads are queued and handed to the pool 32 at a time from a pool thread; the warm-address, warm-slot and transient-storage collections and the tracked storage map are keyed by SipHash-2-4 under a per-process secret instead of sorted, which keeps the hash-flood resistance the sorted structures were chosen for. Expected ~10 ms/block, to be measured against the sequential and parallel profiles.
6. **§11.2 arbitrary-*B*** and **§11.5.3 keccak** — only if §5 is ever reopened, and as a tidy-up respectively.

**The case as it stands.** The layout is feasible (INV-1 holds), the space argument is small and conditional, the root-computation argument is refuted, and the read-path argument turns out to be a parallel-execution argument in disguise. The steady-state profile is the right one and it says the node is CPU-bound on work the sequential pass should not be doing. That is fixable in the code that exists. Reopen §5 only if, after steps 1–3, a measured share of block time remains that RocksDB cannot reach.

### 4.4 Why the state-layer and cache changes did not move p50 (2026-09-13)

The engine thread is on CPU for 85 to 95% of the exec window (scheduler run time vs wall per burst), so the median is per-transaction CPU on one thread. The cross-block cache and the state-layer branch removed waiting from a thread that was barely waiting; each gain was real, about 10 ms, and inside the ±10% hour-to-hour noise. Young GC (4 per block, 7 ms each) turned out to land mostly outside import windows: allocation is continuous at about 225 MB/s, a quarter of it Java BigInteger ECDH in RLPx handshakes. The node now runs with a 16 GB heap and an 8 GB young generation (80 collections per hour at 28 ms), which removes background CPU but was never going to change the median.

Every further A/B needs absolute per-phase time over the same hours of day, which is what `perf/block-phase-timers` adds: `BlockImportTimings` logs, per `Import #N` and `Fork choice #N`, the wall time of header validation, world state lookup, speculative dispatch, reused vs re-executed transactions, post-execution, state root, trie log, commit, body validation and block storage, plus thread CPU time, GC time and the remainder, and exports them as the histogram `besu_block_processing_import_phase_seconds{phase}`. The fork choice update's 50 to 115 ms of CPU on the same thread per slot is unexplained and gets the same breakdown.

### 4.5 First phase-timer readings and the EVM v2 port (2026-09-13)

With the phase timers on the node: execute (28% of transactions re-executed on the import thread at 1.46 ms each) is 79% of newPayload; reuse of the other 72% costs 4%; root 6%, trie log 3%, validation and store 3%. The other clients all pre-warm and execute sequentially; for Besu that would cost about 20 ms more than the optimistic scheme, so the scheme is not the gap. The gap is per-transaction interpreter cost: 65% of re-execution CPU is the EVM loop itself.

`perf/evm-v2-poc` brings the closed proof-of-concept interpreter onto main behind `--Xevm-v2`. On a pure-arithmetic loop in evmtool, warmed: v1 standard 91 Mgas/s, v1 with optimized opcodes (the node's default) 130 Mgas/s, v2 196 Mgas/s. If that ratio carries into the 65% interpreter share of re-execution, execute drops by roughly a fifth, from 98 to about 77 ms mean. The node measurement, same hours of day, execute per re-executed transaction, decides.

Deployed with `Xevm-v2=true` on 2026-09-13 10:31 UTC: ms per Mgas 4.44 to 3.04, p50 111 to 90 ms, p95 290 to 171, every gas bucket 20 to 30% faster on the first 46 blocks. The evmtool arithmetic loop turned out to be a poor yardstick on the laptop (the morning's 770 versus 510 ms was measured while the reference tests ran; unloaded, v1 with optimized opcodes and every v2 build tie at about 350 ms), so the gain is in state, calldata and memory heavy code, and the three loop follow-ups (CALLDATALOAD direct read, tracer skip, loop-local pc and gas) can only be ranked on the node, each as its own commit.
