# Reference Test Execution and Tracing Guide

This document explains how to run Ethereum reference tests in Besu and how to enable JSON tracing during test and block execution. This is useful for debugging EVM behavior, inspecting opcode execution, and verifying correctness against official test vectors.

## Running the Reference Tests

To run the Ethereum reference tests included in the Besu codebase, use the following Gradle task:

```bash
./gradlew referenceTests
```

This will execute the available test suites (such as GeneralStateTests and execution-spec-tests) and validate Besu's EVM behavior.

> **Note:**
> - Out-of-memory (OOM) errors are common due to the size and number of tests. You may need to increase the heap size using `-Xmx` (e.g., `./gradlew referenceTests -Dorg.gradle.jvmargs="-Xmx8g"`)

## Filtering Execution Spec Tests by Hardfork or EIP

Execution-spec-tests are generated with class names that reflect their hardfork and EIP directory structure. This allows targeted test execution using standard Gradle `--tests` filters.

### By hardfork

```bash
# Run all Prague execution spec tests (blockchain + state)
./gradlew referenceTests --tests "*ExecutionSpec*_prague_*"

# Run only Amsterdam state tests
./gradlew referenceTests --tests "*ExecutionSpecStateTest_amsterdam_*"

# Run all Cancun blockchain tests
./gradlew referenceTests --tests "*ExecutionSpecBlockchainTest_cancun_*"
```

### By EIP

```bash
# Run only EIP-7702 tests
./gradlew referenceTests --tests "*eip7702*"

# Run only EIP-4844 blob tests
./gradlew referenceTests --tests "*eip4844*"
```

### By hardfork + EIP

```bash
# Run Prague EIP-2537 BLS precompile tests specifically
./gradlew referenceTests --tests "*_prague_eip2537_*"
```

### Static (legacy) tests

```bash
# Run all static legacy tests
./gradlew referenceTests --tests "*ExecutionSpec*_static_*"

# Run a specific static test category
./gradlew referenceTests --tests "*_static_stCreate2_*"
```

### Generated class name format

Test classes follow the pattern:
```
ExecutionSpec{Blockchain,State}Test_{hardfork}_{eip_or_topic}_{batch_index}
```

For example:
- `ExecutionSpecBlockchainTest_prague_eip7702_set_code_tx_0`
- `ExecutionSpecStateTest_cancun_eip4844_blobs_2`
- `ExecutionSpecBlockchainTest_static_stCreate2_1`
- `ExecutionSpecBlockchainTest_frontier_opcodes_0`

> **Note:** These hardfork/EIP filters apply only to execution-spec-tests. The legacy `GeneralStateReferenceTest` and `BlockchainReferenceTest` classes still use sequential numbering. For those, use the runtime system properties `test.ethereum.state.eips` and `test.ethereum.include` instead.

## Hive-Equivalent Fixture Runners (evmtool)

The `referenceTests` task above drives **block import** and **state transition** directly. It never
touches the Engine API, so payload schema, JSON-RPC error codes, fork support and the blob schedule
have no coverage there at all. Upstream, that gap is filled by hive's `consume-engine` simulator —
which spins up a Besu container per fixture group and takes hours.

`evmtool` replays the same fixture trees through the same Besu code, in process, in minutes:

| hive | Gradle task | Besu code path |
|------|-------------|----------------|
| `--sim ethereum/eels/consume-engine` | `consumeEngineTests` | `engine_newPayloadVX` + `engine_forkchoiceUpdatedVX` over `blockchain_tests_engine` |
| `--sim ethereum/eels/consume-rlp` | `consumeRlpTests` | RLP block import over `blockchain_tests` |

Both reuse the same devnet fixture download/extract as the reference tests (`extractDevnetFixtures`
— no separate download) and **fail the build on any fixture failure**.

```bash
# Full consume-engine equivalent
./gradlew consumeEngineTests

# Full consume-rlp equivalent
./gradlew consumeRlpTests

# Both, in one command
./gradlew consumeEngineTests consumeRlpTests
```

`consume-rlp` is not redundant with `consume-engine`: `blockchain_tests_engine` has no pre-merge
fork groups, so `for_frontier`, `for_homestead`, `for_tangerinewhistle`, `for_spuriousdragon`,
`for_byzantium`, `for_constantinoplefix`, `for_istanbul`, `for_berlin` and `for_london` exist only
in `blockchain_tests`.

### Translating a hive invocation

The properties are named after the hive flags they stand in for, and are shared by both tasks, so
one command covers both simulators.

| hive flag | Gradle property |
|-----------|-----------------|
| `--sim.limit=<regex>` (with `--sim.limit.exact=false`) | `-PsimLimit=<regex>`, taken verbatim |
| `--sim.parallelism=N` | `-PsimParallelism=N` (default: available processors) |
| *no equivalent* | `-PsimPath=<subdir>` — scope to one fixture subdirectory, e.g. `for_amsterdam` |

So this hive run:

```bash
./hive --sim ethereum/eels/consume-engine --client besu --sim.parallelism=6 \
  --sim.limit='.*(2780|7708|7928|8282).*' --sim.limit.exact=false ...
```

becomes:

```bash
./gradlew consumeEngineTests -PsimParallelism=6 -PsimLimit='.*(2780|7708|7928|8282).*'
```

and swapping the task for `consumeRlpTests` covers `--sim ethereum/eels/consume-rlp`. Quote the
pattern — the shell would otherwise glob it.

`-PsimPath` has no hive counterpart but is worth reaching for: scoping to a fork group is far
cheaper than filtering the whole tree, since a filter still has to read every fixture file.

```bash
./gradlew consumeEngineTests -PsimPath=for_amsterdam -PsimLimit='.*(7928|8282).*'
```

#### How `-PsimLimit` is translated

The value is a hive regex and is accepted as written, but it is not handed to `evmtool` untouched.
`evmtool`'s `--test-name` escapes `.` to a literal before expanding `*` to `.*`, because pytest node
ids contain `.py` and a bare `.` is far more often meant literally. A hive pattern passed straight
through would compile with its `.*` turned into `\..*` and match nothing, so the task rewrites `.*`
to `*` first, which `evmtool` expands straight back to `.*`.

The two agree on match semantics: hive matches partially under `--sim.limit.exact=false`, and the
leading/trailing `.*` that hive patterns conventionally carry survive the rewrite, so the
whole-node-id match `evmtool` performs selects the same tests. A pattern with no wildcard at all
falls through to `evmtool`'s case-insensitive substring form, also a partial match.

Two things are **not** translated, and are rare in `--sim.limit` values:

- a `?` quantifier — `evmtool` reads `?` as a single-character wildcard;
- a bare `.` meant as any-character — `evmtool` reads it as a literal `.`.

`evmtool`'s regex is also case-insensitive where hive's Go regex is case-sensitive. Irrelevant for a
digit-only alternation, relevant if you filter on fork names.

A malformed pattern is compiled before any fixture is read, so it fails immediately with exit 1
rather than silently running nothing. An empty run is an error too: a run that executed no test
never reports success.

The `[`, `]`, `(` and `)` common in pytest node ids are regex metacharacters. `(` and `)` are what
make the `(a|b|c)` alternation work; escape them when you want them literal:

```bash
# WRONG: '[' opens a character class -> rejected before any test runs
./gradlew consumeEngineTests -PsimLimit='.*[fork_Amsterdam.*'
#   Invalid --test-name pattern: Unclosed character class. …

# RIGHT
./gradlew consumeEngineTests -PsimLimit='.*\[fork_Amsterdam.*'
```

### Reproducing a published hive run

Two tasks per simulator carry the filter of a published hive run, so reproducing one is a task name
rather than a copied regex. They ignore `-PsimLimit` — the preset *is* the point — but still take
`-PsimParallelism`.

| Task | Mirrors | `--sim.limit` |
|------|---------|---------------|
| `consumeEngineTestsGlamsterdam` / `consumeRlpTestsGlamsterdam` | [glamsterdam](https://hive.ethpandaops.io/#/test/glamsterdam/1787867407-75e9079746599601ca1c6f8ffd9aab2a) | `.*fork_(Amsterdam\|BPO2ToAmsterdamAtTime15k\|Osaka).*` |
| `consumeEngineTestsGlamsterdamQuick` / `consumeRlpTestsGlamsterdamQuick` | [glamsterdam-quick](https://hive.ethpandaops.io/#/test/glamsterdam-quick/1787875966-3c9b3411ee5e78d8b8c2fa25af6e899f) | the 20-EIP alternation |

```bash
./gradlew consumeEngineTestsGlamsterdam        # the full published sweep
./gradlew consumeEngineTestsGlamsterdamQuick   # the EIP-scoped sweep
```

Measured against the pinned fixtures:

| Task | tests | passed | failed | wall clock |
|---|---|---|---|---|
| `consumeEngineTestsGlamsterdam` | 42501 | 42377 | 124 | ~31s |
| `consumeEngineTestsGlamsterdamQuick` | 3959 | 3956 | 3 | ~20s |
| `consumeRlpTestsGlamsterdamQuick` | 3954 | 3954 | 0 | ~22s |
| `consumeRlpTestsGlamsterdam` | 42452 | 37965 | 4487 | ~51s |

> **`consumeRlpTests` is not yet trustworthy on devnet blob forks.** 4463 of those 4487 failures are
> blob tests (`cancun/eip4844_blobs`, `osaka/eip7918_blob_reserve_price`) and none of them are Besu
> bugs: `BlockchainTestSubCommand.getSchedules()` builds one schedule from
> `ReferenceTestProtocolSchedules.create(evmConfiguration)`, the overload that knows nothing about
> the fixture's `config.blobSchedule`, so devnet blob target/max never reach the validator.
> `engine-test` reads each fixture's blob schedule and caches a schedule per distinct one; the same
> treatment has not been given to `block-test`, and `BlockchainReferenceTestCaseSpec` has no
> `getBlobScheduleOptions()` to read. Until that is fixed, use the engine tasks as the signal and
> read the RLP ones as blob-blind.

The published runs pull `tests-glamsterdam-devnet@v8.1.2` while `devnetTarConfig` pins `v8.1.1`, so
counts differ slightly from hive's — the glamsterdam run selects 42588 tests upstream against 42501
here. Note that the number the hive UI shows most prominently is the *pass* count (42140), not the
number of tests run.

### Worked example

Against the pinned fixtures, scoped to `for_amsterdam` with the Glamsterdam EIP set:

```bash
L='.*(2780|7708|7732|7778|7843|7928|7954|7975|7976|7981|7997|8024|8037|8038|8045|8061|8070|8159|8246|8282).*'
./gradlew consumeEngineTests -PsimPath=for_amsterdam -PsimLimit="$L" -PsimParallelism=12
./gradlew consumeRlpTests    -PsimPath=for_amsterdam -PsimLimit="$L" -PsimParallelism=12
```

| | tests | passed | failed | wall clock |
|---|---|---|---|---|
| `consumeRlpTests` | 3875 | 3875 | 0 | ~20s |
| `consumeEngineTests` | 3878 | 3875 | 3 | ~20s |

The three failures are the point of the exercise — they are invisible to `consumeRlpTests` and to
`referenceTests`, because they are Engine API behaviour rather than block validity:

```
eip7928 test_bal_invalid_engine_payload_encoding[empty_byte_string | rlp_non_list | rlp_truncated_list]
  payload 0: expected Engine API error code -32602, got success response with status INVALID
```

### Fixture version

Both tasks run against the tarball pinned by `devnetTarConfig` in
`ethereum/referencetests/build.gradle`. A hive run pins its own via `--sim.buildarg fixtures=<url>`,
so check the two match before comparing results. To move the pinned version:

1. Update `version` in the `devnetTarConfig` dependency.
2. Run `./gradlew --write-verification-metadata sha256` — Besu uses dependency verification, and
   without a matching checksum in `gradle/verification-metadata.xml` the extract fails outright.
3. Commit both together.

To try a different tarball without repinning, extract it yourself and point the binary at it (see
below).

### What these tasks do not cover

`state_tests` are the EVM/state-transition-only slice, consumed by no hive simulator and by neither
task above. `stateTestsDevnet` runs them, taking the same `-PsimLimit` / `-PsimParallelism` /
`-PsimPath` properties:

```bash
./gradlew stateTestsDevnet -PsimPath=for_amsterdam
```

Fixture files that cannot be read as a test of the expected kind are reported separately under
"Unreadable" and do **not** count as failures — they say nothing about Besu. As of the currently
pinned fixtures that is 14 `state_tests` files (`test_bad_v_r_s`), which carry a pre-signed
transaction in `post[].txbytes` rather than a `secretKey` that `StateTestVersionedTransaction` can
sign with.

### Running the binary directly

For an ad-hoc run against any fixtures directory — a tarball you extracted yourself, or a single
file — build the `evm` binary once and call the subcommands:

```bash
./gradlew :ethereum:evmtool:installDist
EVM=ethereum/evmtool/build/install/evmtool/bin/evmtool

$EVM engine-test --workers 8 <path-to>/blockchain_tests_engine/    # consume-engine
$EVM block-test  --workers 8 <path-to>/blockchain_tests/           # consume-rlp
```

A directory argument is walked recursively and spread over `--workers` workers. `--test-name` is the
raw form of `-PsimLimit`, and takes the *translated* expression (`*(7928|8282)*`, not `.*(…).*`).
`--json-array` emits machine-readable results (`[{name, pass, fork, lastBlockHash, error}]`) and
nothing else, so the exit code is what reports an empty or failed run.

`engine-test` prints failures and a final summary only; `--verbose` adds a line per test.
`block-test` logs every imported block, so pipe through `grep -v 'Imported in'` for a quiet run.

> The Gradle-extracted fixtures live at
> `ethereum/referencetests/build/execution-spec-devnet-tests/fixtures/`, so you can point the binary
> there after running `extractDevnetFixtures` once.

> **Tip:** if a verbose run makes the terminal flicker (Gradle's animated console repainting as
> output streams), add `--console=plain`.

## Enabling JSON Tracing

Besu supports detailed opcode-level JSON tracing. You can enable it using either a JVM system property or an environment variable.

### Option 1: JVM System Property

```bash
-Dbesu.debug.traceBlocks=true
```

### Option 2: Environment Variable

```bash
export BESU_TRACE_BLOCKS=true
```

This enables a fallback implementation of `BlockAwareOperationTracer` if no plugin is configured. The default tracer used is `BlockAwareJsonTracer`.

JSON trace output does not appear in the console. To view it, open the associated Gradle test report (usually located in `build/reports/tests/test/index.html`) and find the specific test case output.

## Trace Contents

When enabled, tracing includes:

- Opcode execution and names
- Stack state
- Gas remaining and gas cost
- Memory size
- Precompile execution
- Contract creation and call frames
- Transaction lifecycle events (start, prepare, end)
- Exceptional halts

Each traced operation emits structured JSON data representing the EVM state at that point.

## Output Format

The tracer prints a complete JSON trace of each block’s execution to standard output at the end of the block:

```
==== JSON Trace for Block <BLOCK_NUMBER> (<BLOCK_HASH>) ====
<trace entries>
```

Example:

```json
{
  "pc": 0,
  "op": "0x60",
  "opName": "PUSH1",
  "gas": 999999,
  "gasCost": 3,
  "stack": [],
  "memSize": 0,
  "depth": 1,
  "refund": 0
}
```

## Tracer Implementation

The tracer is implemented in:

```
org.hyperledger.besu.ethereum.mainnet.BlockAwareJsonTracer
```

It uses a `StringWriter` and a `StandardJsonTracer` to collect and format execution traces. Output is flushed during the `traceEndBlock(...)` callback.

The `BlockAwareJsonTracer` is enabled automatically when no plugin provides a custom tracer and one of the tracing flags is set:

```java
if (Boolean.getBoolean("besu.debug.traceBlocks")
    || "true".equalsIgnoreCase(System.getenv("BESU_TRACE_BLOCKS"))) {
  return new BlockAwareJsonTracer();
}
```

## Notes

- Tracing is for debugging purposes only and should not be enabled in production environments.
- Trace output can become large, especially for blocks with many transactions.
- Tracing does not affect EVM execution semantics.

## Resources

- [Ethereum Execution Spec Tests (ethereum/execution-spec-tests)](https://github.com/ethereum/execution-spec-tests)
- [Ethereum Reference Tests (ethereum/tests)](https://github.com/ethereum/tests)
- [EVM Opcodes Reference](https://www.evm.codes/)