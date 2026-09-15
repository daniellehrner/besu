# EVM v2: a uniform opcode contract behind a generated switch

Status: plan, 2026-09-14. Branch `perf/evm-v2-poc`.

## Why

`runToHaltV2` is a 2.7k-bytecode switch. Its own time is 18-28% of the execute phase on
mainnet and cannot be profiled below the method. What the switch does per opcode today:

- the op checks its own stack requirements, returning `UNDERFLOW_RESPONSE` / `OVERFLOW_RESPONSE`
- the op computes its gas and checks it against the frame, or returns the cost for the loop to charge
- the op returns an `OperationResult` object (allocated per call for every non-fixed-cost op)
- the loop reads three accessors off that object, syncs `pc`/`gas` to the frame for every op not
  in `LOOP_OWNS_STATE`, and re-reads them afterwards

The uniform contract moves the first, second and fourth items into the loop, driven by per-opcode
metadata, and replaces the object with a packed `long`.

## The contract

```java
public interface OpcodeV2 {
  int stackIn();                // items read or popped
  int stackOut();               // items left on the stack in their place
  int flags();                  // see below
  long gasCost(MessageFrame frame, long[] s, int top, long gasRemaining);
  long execute(MessageFrame frame, long[] s, int top, int pc, long gasRemaining);
}
```

`gasCost` returns the full cost of the operation, including memory expansion and warm/cold
access, or a negative halt code. Warming is a side effect of costing, as it is today; an
out-of-gas after warming reverts the whole frame, so nothing observable differs.

`execute` returns a packed `long`:

| bits | meaning |
|---|---|
| 0-15 | new stack top |
| 16-31 | new `pc` when `SETS_PC` is set in flags; otherwise unused |
| 32-55 | extra gas charged inside execute (SSTORE refunds/surcharges, CALL value transfer) |
| 56-63 | halt code, 0 for none |

Flags, one bit each: `SETS_PC` (JUMP, JUMPI, PUSH), `NEEDS_FRAME_SYNC` (anything that creates a
child frame, halts the frame, or reads `frame.getRemainingGas()` / `frame.getPC()` indirectly:
CALL family, CREATE family, RETURN, REVERT, STOP, SELFDESTRUCT, INVALID, GAS, PC),
`MAY_CHANGE_STATE` (the loop checks `frame.getState()` only after these).

Per-fork tables: `OpcodeV2[] ops` plus, mirrored for the loop's hot path, `byte[] stackIn`,
`byte[] stackOut`, `byte[] flags`, `long[] fixedGas` (Long.MIN_VALUE when dynamic). The loop
reads the primitive arrays, not the objects, for the checks.

## The loop

The loop owns `pc`, `gas` and the stack top as locals, checks stack and static gas once per
basic block from the prepared analysis (phase 6, block metadata), and dispatches through a
generated switch to ops that return a packed `long`. The two-method split of the contract is
used at analysis time: `stackIn`, `stackOut` and `fixedGas` feed the block metadata; at run
time only `execute` is called and dynamic costs come back inside the result.

```java
int pc = frame.getPC(), top = frame.stackTopV2();
long gas = frame.getRemainingGas();
int block = analysis.blockAt(pc);
gas = enterBlock(block);                    // stackMin/stackMax check, gas -= staticGas
while (true) {
  final int opcode = code[pc];
  final long r = switch (opcode) {          // generated; one call per case, nothing else
    case ADD    -> Add.exec(s, top);        // pure ops: stack only, static cost, no frame
    case MLOAD  -> Mload.exec(frame, s, top);   // dynamic cost returned in the result
    case JUMPI  -> Jumpi.exec(s, top, pc);      // sets pc; the loop re-enters a block
    case CALL   -> { sync(frame, pc, gas, top); yield Call.exec(frame, s, top, gas); }
    ...
  };
  if (halt(r) != 0) { sync; setHalt(halt(r)); return; }
  top = top(r);
  gas -= dynamicGas(r); if (gas < 0) { sync; setHalt(OUT_OF_GAS); return; }
  if (setsPc(r)) { pc = pc(r); block = analysis.blockAt(pc); gas = enterBlock(block); }
  else pc++;
  if (endsBlock[opcode]) {
    if (frame.getState() != CODE_EXECUTING) { sync; return; }   // child frame or halt
    block++; gas = enterBlock(block);         // fall-through into the next block
  }
}
```

Blocks end at JUMPDEST boundaries, after JUMP and JUMPI, and at every op that reads or changes
gas or control flow outside the stack: GAS, the CALL and CREATE families, RETURN, REVERT,
STOP, SELFDESTRUCT, INVALID. Those therefore always see exact remaining gas. A stack fault
anywhere in a block is reported at block entry; that is observably identical because an
exceptional halt reverts the frame and consumes all its gas either way.

Two op shapes, chosen by the generator from the op's category: pure ops take the stack and
return the new top as an `int`; frame ops take the frame and return the packed `long`. The
frame is synced (pc, gas, top) only before frame ops flagged `NEEDS_FRAME_SYNC`, at block ends
when the state changed, and on halts.

Tracers get a separate loop generated from the same tables with per-op checks and the two
hooks, because per-op gas is what they observe. Block import never runs it.

Until phase 6 lands, the same loop runs with one block per op: `enterBlock` degenerates to
the per-op stack and fixed-gas check from the tables. That is the phase 1 shape.

## Calls and creates

The suspend/resume protocol does not change: CALL builds the child frame, pushes it on the frame
stack, sets the parent to `CODE_SUSPENDED` and returns; the message processor runs the child and
calls `complete`, which pushes the result and sets the parent back to `CODE_EXECUTING`; the loop
is re-entered for the parent at the stored `pc`. Under the new contract:

- `gasCost` does `callOperationGasCost` including delegation resolution, account existence and
  the new-account surcharge. The account it looked up is kept in a per-frame scratch slot
  (`frame.opScratchAccount`) so `execute` does not look it up again.
- `execute` receives `gasRemaining` after the cost was charged, so the 63/64 rule and the
  stipend are computed from the parameter, not from the frame. The frame is synced before
  execute because of `NEEDS_FRAME_SYNC`, so child-frame construction sees the right `pc`, gas
  and stack top.
- `complete` is unchanged, except that it pushes onto the stack through the frame's stored top,
  which is valid because the frame was synced before the op ran.
- Soft failures (insufficient balance, depth) push 0 and return the stipend as a negative extra
  gas, i.e. a refund, in bits 32-55; the encoding is signed for that field.

CREATE follows the same shape; the EIP-3860 init-code check moves into `gasCost` where the
size is already on the stack.

## Migration order

1. Interface, packed result, per-fork tables, the generated switch and the new loop, selected
   by `--Xevm-v2-loop=TABLE` (default stays the current loop). Every existing op is called from
   its case as today and its `OperationResult` is translated after the switch. Reference tests
   green on that loop before any op is rewritten. 2 days.
2. Differential fuzzer: random bytecode, random calldata, both loops on identical frames,
   compare stack, gas, pc, memory, halt reason after each op. Runs in the evm test suite with a
   fixed seed and locally with a large one. 1 day.
3. Rewrite ops family by family to the native contract, running the fuzzer after each family:
   stack and arithmetic (fixed cost, no frame), memory and copy ops, environment and block ops,
   storage and transient storage, logs, then jumps. 3-4 days.
4. Calls, creates and halts. 2 days.
5. Delete the translation, measure on the node against the switch loop, then delete the loser.
   1 day plus a node window.

Roughly two weeks. Steps 1-2 already give the answer to the key risk below.

### Phase 1 as built (2026-09-14)

`org.hyperledger.besu.evm.v2.loop`: `LoopResult` (the packed `long`), `LoopTables` (stack
in/out per opcode, fixed gas and flags), `TableLoop` (the loop). Selected by
`--Xevm-v2-loop=TABLE` in Besu and evmtool, `-Dtest.evm.v2.loop=TABLE` for the reference
tests. Deviations from the sketch above, and why:

- The stack table is written out by hand from the instruction set rather than read from the
  registered operations: the registry declares JUMP as consuming two items (now fixed) and
  carries placeholders for DUPN, SWAPN and EXCHANGE, and the loop's checks cannot depend on
  declarations nothing else enforces. `LoopTablesTest` cross-checks the two. Opcodes a fork
  lacks get zero entries so that the invalid-operation halt wins over a stack fault, as it
  does in the switch loop.
- The switch is written by hand, not generated. The translation of old-contract results keeps
  every case a one-liner already; the generator can come with phase 3 when the op classes
  carry the metadata.
- POP, JUMPDEST, PUSH0, DUP1-16 and SWAP1-16 are on the native contract as the pattern: the
  loop checks their stack use and charges their fixed gas from the tables, and the case
  returns `LoopResult.ok(newTop, pc + 1)`. Everything else is translated.
- The differential test (`TableLoopTest`) is phase 2's seed: fixed programs, a state-test
  fixture, and 3000 seeded random programs on both loops with and without a tracer. Frames
  must be put into `CODE_EXECUTING` by the harness, as the message processor does, or both
  loops return at once and the test compares nothing. A mutation of POP is the check that it
  bites.

### Phase 3 as built (2026-09-14)

On the native contract: STOP, the arithmetic, comparison and bitwise ops, EXP, KECCAK256,
the environment and block constants, BALANCE, CALLDATALOAD/SIZE/COPY, CODESIZE/COPY,
EXTCODESIZE/COPY/HASH, RETURNDATASIZE/COPY, BLOCKHASH, BLOBHASH, SELFBALANCE, POP, MLOAD,
MSTORE, MSTORE8, SLOAD, JUMP, JUMPI, PC, MSIZE, GAS, JUMPDEST, TLOAD, TSTORE, MCOPY,
PUSH0-32, DUP, SWAP, LOG0-4, RETURN, REVERT, INVALID. Ops with a fixed cost have it in the
table and return `ok(top, pc)`; ops with a dynamic cost compute it, check it against the
gas the loop passes in, and return it in the result. Ops that end the frame carry the
`STATE` flag so the loop checks the frame state after them.

Still translated from `OperationResult`: SSTORE, SELFDESTRUCT, PAY, the CALL family, CREATE
and CREATE2, DUPN, SWAPN, EXCHANGE. All of them move gas on the frame themselves (state gas
reservoir, child gas, refunds), so the frame has to be synced around them either way, and
the translation costs one small object per call. Phase 4 either leaves them there or gives
them a `SYNC` flag with the same contract; the node measurement decides whether it is worth
the work.

Found on the way: the v2 SIGNEXTEND helper compared the byte index as a signed long, so an
index of 2^63 or more corrupted the result (fixed, 9cc405e7a0), and the registered JUMP
operation declared two stack items (fixed, a269d38263).

## Dispatch: why the switch stays

`ops[opcode].execute(...)` is a single call site that sees every opcode class, so C2 cannot
inline it: an interface call costs an itable scan plus an indirect call, an abstract-class call
a vtable load plus an indirect call, and neither is ever inlined. A `tableswitch` is one
indirect jump, and each case is a direct call that C2 inlines. The only way to get inlined
dispatch is one call site per opcode, which is a switch.

So the contract, the metadata tables, the packed `long` and the loop-owned `pc`/`gas`/`top`
all stay as described, and the dispatch is a switch whose cases contain nothing but the call
to the concrete op. The switch is generated at build time from the op classes by an annotation
processor, so it is not maintained by hand and cannot drift from the fork tables.

Step 0, before anything else: run evmtool with `-XX:+UnlockDiagnosticVMOptions
-XX:+PrintInlining` on the current loop and record which cases C2 inlines and which it refuses
for size. `runToHaltV2` is 2.7k bytecodes, and if the later cases already pay a call because
the compiled method exceeded its budget, the thin generated switch is worth more than
expected; the case bodies must then stay one call each, and the cold ops (calls, creates,
precompile-heavy ops) are deliberately kept out of line to leave the budget for the hot twenty.

## Step 0 results: what C2 inlines into the current loop (2026-09-14)

Method: run evmtool with `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation
-XX:LogFile=comp.xml`, then take the last C2 `<task>` for `EVM runToHaltV2` and count
`inline_success` / `inline_fail` for the call sites at parse depth 1 (the loop's own sites).
Two profiles were used: a single hot loop (ADD, MSTORE, MLOAD, KECCAK, GT, JUMPI) and 900
GeneralStateTests fixtures through `evmtool state-test --fork=Prague`, which exercise every
opcode in mainnet-like proportions.

| profile | op call sites inlined | refused | main refusal |
|---|---|---|---|
| single hot loop | all but 3 | 3 | callee already compiled into a big method |
| 900 state tests | 1 op class | 38 hot ops "too big", 41 cold "low frequency" | per-site size limit |

Under the realistic profile C2 inlines essentially no opcode into the loop: ADD, SUB, MUL,
MLOAD, MSTORE, SLOAD, KECCAK256, JUMPI and thirty others are refused as "too big". The rule
behind it: a callee over `MaxInlineSize` (35 bytecodes) is inlined only when C2 rates the
site hot, and then only up to `FreqInlineSize` (325 bytecodes). Spread across 150 cases no
single site rates hot enough, so the 35-byte limit applies, and no op body is under it.
Forcing inlining with a compiler directives file (`-XX:CompilerDirectivesFile`, example in
`inline-directives-example.json`) fails differently: after 28 sites the total inlined
bytecode passes `DesiredMethodLimit` (8000, not tunable in product builds) and every further
site is refused.

So today the switch pays a static call per opcode, the same cost class as a virtual call.
The advantage of a switch is only realised if the loop is built to the JIT's limits:

- the loop itself small: one call per case, no checks in the cases (today 1936 bytecodes
  for the loop alone, out of an 8000 budget shared with everything inlined into it)
- hot op bodies at or under 35 bytecodes, so they inline regardless of the frequency rating;
  the op is a thin wrapper and the real work is a `StackArithmetic` helper inlined at depth 2
- the helpers themselves under 325 bytecodes: `add` is 357 and `sub` 398 today, so the
  256-bit add and sub can never inline anywhere and need rewriting as plain carry chains
  (60-80 bytecodes each); `mul` 116, `shl`/`shr` 136, `eq` 105 are fine
- a deliberate list of the ~20 ops that get the budget; everything else stays a call

The same LogCompilation parse is the acceptance test for the new loop: the hot list must
show `inline_success` at depth 1 and its helpers at depth 2, and no `DesiredMethodLimit`
refusal may appear.

### Inlining into the table loop, measured (2026-09-14, after phase 3)

Same method and profile as step 0, on `TableLoop.run` (3968 bytecodes; the tableswitch alone
is about 1 KB of it). 86 depth-1 sites inlined, 107 refused: the `LoopResult` accessors, the
frame accessors, `add` and `pushLong` inline; `sub`, the comparisons (56 bytecodes), the
bitwise ops (60-74), `mul`/`div`/`mod` (117), the shifts (137), `dup` (55), `swap` (113),
`pushAddress`/`pushWei` (~60) and every `exec` are refused as "too big", i.e. over the
35-bytecode limit for a site C2 does not rate hot. `pushFromBytes` at 448 was refused as
"hot method too big" and is now split so the fast path is 314.

So the loop shape alone does not buy inlining: 256-bit helpers cannot be written in 35
bytecodes, and C2 rates a site hot by its share of the loop's profile, which no single
opcode reaches. Two ways forward, to be measured on the node with this build:

- `-XX:MaxInlineSize=<n>` on the node's JVM (a product flag; 120 covers the helpers, 200 the
  `exec` methods). Global, so watch code cache size and total compile time.
- accept the call per opcode and take the gains the loop gives without inlining: no result
  object, no per-op frame sync, no duplicated checks. The A/B `TABLE` vs `SWITCH` on the same
  build isolates exactly that.

## Phase 6: a prepared representation per contract

The code cache (256 MB, memory bound, keyed by code hash, 98.8% hit rate, zero evictions at
4.5k entries) makes a one-time analysis per contract cheap: a linear pass at first use, stored
on the `Code` object next to the JUMPDEST bitmask and charged to the cache's footprint
estimator. In order of expected gain:

1. Basic-block metadata: split at JUMPDEST and after JUMP, JUMPI, STOP, RETURN, REVERT,
   INVALID, SELFDESTRUCT; per block the static gas, the lowest stack height needed and the
   highest reached. The loop checks stack and static gas once per block and charges only
   dynamic costs per op. Three ints per block. Depends on the tables from phase 1.
2. Resolved jump targets: a PUSH directly before JUMP or JUMPI is validated at analysis time
   and the jump becomes a direct pc assignment; the pair also terminates its block.
3. Decoded PUSH immediates: four longs per PUSH site plus a pc-to-site index, about 2.5x the
   code size. Only after a measurement shows what the masked reads left on the table.
4. Fused sequences (PUSH+arithmetic, DUP/SWAP pairs): last, each one adds a switch case.

Watch `code_cache_evictions` and `code_cache_eviction_weight` on the node once any of these
is deployed.

## Table loop on the node, and why the design changed (2026-09-15)

Overnight A/B on e2145a6, same build, same 16 GB heap, mainnet at about 30 M gas per block:

| import phase, ms         | SWITCH (604 blocks) | TABLE (1486 blocks) |
|--------------------------|--------------------:|--------------------:|
| execute p50 / mean       | 47.8 / 55.5         | 51.5 / 59.5         |
| per re-executed tx       | 0.481               | 0.487               |
| cpu p50                  | 62.6                | 64.8                |

A wash: the per-transaction cost is identical within 1%, the execute mean difference is
the higher re-execution rate of the TABLE window (67% reuse against 70%).

The wall profile of the TABLE build explains it. Share of the time under the loop, all EVM
threads:

| under the loop                     | SWITCH b00543a | TABLE e2145a6 |
|------------------------------------|---------------:|--------------:|
| loop self time plus PUSH decode    | 30.2%          | 29.8%         |
| SLOAD                              | 25.8%          | 19.1%         |

Removing the per-op stack and gas checks from every op body moved nothing. The JFR sampler
attributes a sample in compiled code to the nearest debug-info point, which in a 30 KB
compiled loop is mostly the scope of the most frequent inlined op, so "PUSH decode 15%"
has to be read together with the loop's own 11 to 15%. What is left in that 30% is the
dispatch itself: one code fetch and one indirect jump through a jump table over about 150
live targets per op, and mainnet bytecode is diverse enough that the branch predictor misses
a large share of them. A mispredict is 15 to 20 cycles, about the whole per-op budget.

The compilation logs rule out the other suspects: both loops are deoptimised and recompiled
the same handful of times during warm-up (class_check and unstable_if traps) and then stay
stable; the VarHandle reads in the PUSH decode inline to plain loads.

Consequences for the design:

- "Dispatch: why the switch stays" above was wrong in its conclusion. A vtable call is one
  indirect jump, predicted by the same hardware as the jump table's indirect jump, so the
  megamorphic call site costs what the switch costs. What the switch bought, inlined case
  bodies, C2 does not deliver at this method size anyway (see "Inlining into the table loop,
  measured").
- What a small loop over op objects does buy is codegen: every op is compiled on its own with
  its own inlining budget, the loop keeps pc, gas and top in registers, and the helpers that
  are real calls inside the 4 KB `TableLoop.run` get inlined. Expected: a few percent of
  execute, not a step change.
- The step change needs fewer dispatches, which is phase 6, and op objects are the natural
  carrier for it: a prepared `Op[]` per code hash holds PUSH objects with decoded limbs,
  per-block stack and gas metadata checked once per block, and fused pairs (PUSH+JUMP,
  PUSH+JUMPI, PUSH+DUP, PUSH+MSTORE) that remove the second dispatch.

### Phase 7 as built: the object loop (2026-09-15)

`--Xevm-v2-loop=VTABLE`, package `org.hyperledger.besu.evm.v2.op`:

- `Op`: abstract class with final `opcode`, `stackIn`, `stackOut`, `fixedGas`, `checksState`,
  `legacy`, all taken from `LoopTables` so the numbers have one source, and
  `long exec(frame, code, s, top, pc, gas)` returning the packed `LoopResult`. An abstract
  class rather than an interface so HotSpot emits a vtable call, not an itable stub.
- Op families as static nested final classes: `StackOps`, `ArithmeticOps`, `MemoryOps`,
  `StateOps`, `EnvOps`, `ControlOps`. Each native op wraps the `exec` static that the table
  loop already called. `LegacyOp` syncs pc, gas and top to the frame, runs the old-contract
  static, catches the stack exceptions and translates the `OperationResult`; `LegacyOps`
  holds calls, creates, SSTORE, SELFDESTRUCT, PAY, DUPN/SWAPN/EXCHANGE and a registry
  wrapper for anything else a fork registers.
- `OpTable`: the 256 objects for one fork. The registry decides which opcodes exist: an
  opcode it lacks becomes `Invalid` whatever the stack holds, so fork gating leaves the loop.
- `VtableLoop.run`: about 40 lines. Reads the op, checks stack in/out and the fixed gas from
  its fields, one virtual call, then the same epilogue as the table loop (legacy marker,
  dynamic gas, state check for STOP/RETURN/REVERT). The tracing variant is a separate method
  so the hot loop carries no tracer branches.
- Tests: `TableLoopTest` is parametrised over TABLE and VTABLE; `OpTableTest` checks every
  fork's table against the registry and `LoopTables` and that no mainnet opcode falls
  through to the registry wrapper.

The switch and table loops stay in the tree and selectable so the three can be compared on
the node and the history shows what was tried.
