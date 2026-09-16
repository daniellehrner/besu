# An EVM v2 interpreter loop shaped for HotSpot C2 on JDK 25

Status: design note, nothing implemented. Written 2026-09-16 against branch
`perf/evm-v2-poc` at `cf7077b`.

Companion to `v2-table-loop-plan.md`, which records the three dispatch designs
that were built and reverted. This note starts from why they all measured the
same, and proposes changing what surrounds the dispatch instead.

## 1. Verdict

Keep the switch. C2 settles the question the node could not: a table of
operation objects is dispatched through an itable stub and is never inlined,
because the call site sees 96 receivers and C2 inlines a virtual site only when
it is monomorphic, bimorphic, or has a receiver above 90 percent
(`TypeProfileWidth` 2, `TypeProfileMajorReceiverPercent` 90). A dense profiled
switch becomes a few compares for the hot opcodes followed by one indirect jump,
with the arms' bodies inlined and loop state in registers.

SWITCH, TABLE and VTABLE all measured the same because they were three
arrangements of the same dispatch, and dispatch was never the expensive part.

The expensive part is on either side of it:

- interpreter state lives in `MessageFrame` rather than in loop locals,
- every operation returns a heap object the loop reads three fields from,
- roughly forty operation classes allocate a fresh one per execution.

A call or a field store kills C2's cached memory state, so each of those costs a
reload on the next opcode.

**What this is not.** The reverted designs replaced the dispatch mechanism and
kept the operation protocol. This proposal keeps the dispatch and replaces the
protocol. If it fails it fails for a different reason than they did.

## 2. The budget

HotSpot refuses to compile any method whose own bytecode exceeds
`HugeMethodLimit` = 8000. It is a *develop* constant, so it cannot be raised in a
product build, and the same gate covers OSR: a loop in an oversized method never
leaves the template interpreter at any tier
(`compilationPolicy.cpp:210`, `:235`).

A second budget of the same size governs inlining, and this one bites. C2's
inline-bytecode counter is initialised with the root method's own size, not zero
(`bytecodeInfo.cpp:50`), so every inlined helper is charged against what is left.
A separate cutoff stops non-trivial inlining past 18000 IR nodes.

Measured with `javap` on `EVM.class`, 2026-09-15:

| Method | Bytecode bytes | Instructions |
|---|---|---|
| `runToHaltV2` | 3253 | 1290 |
| `runToHalt` (v1) | 1901 | 643 |

One `tableswitch` covering 0..255 with 91 distinct branch targets. The loop
starts its inlining life 41 percent spent, leaving ~4747 bytes for every helper
inlined into it. Giving all 91 arms inlined bodies at a realistic ~55 bytes each
would exceed the limit and the method would stop being compiled altogether.

Splitting the switch is therefore not stylistic. GraalVM's Truffle bytecode DSL
partitions its generated interpreters at exactly 8000 bytecodes for the same
reason.

## 3. Five rules C2 imposes

Read from JDK 25 GA sources, not folklore.

**R1. One method, under 8000 bytecodes, counting everything inlined into it.**
The huge-method gate also blocks OSR; the inlining counter starts at the root's
own size.

**R2. Interpreter state in locals, never in fields.** C2 keeps SSA values in
registers; a field is a memory access on its alias slice, and any non-inlined
call kills the slice. pc, stack pointer, gas, code array and stack array must be
loop locals, written back only at a boundary. No counted-loop optimisations
apply to a dispatch loop, so bounds checks survive except through dominance and
range-check smearing: index from one local off one local array reference.

**R3. Status codes, not objects; no exceptions for common halts.** A per-op
result object is scalar-replaced only when the op is inlined and the object does
not reach a merge with other allocations (`escape.cpp:2907`, "is merged with
other objects") — which is exactly what a shared post-switch result variable
creates. Untaken exception handlers are pruned since JDK 22, but taken ones cost
a real unwind.

**R4. Hot opcodes first, in source order.** Parse-time inlining walks basic
blocks in reverse post-order (`parse1.cpp:655`), so budget is consumed in case
order, not profile order. `FreqInlineSize` is 325 bytecodes and `InlineSmallCode`
1000 machine bytes; a helper compiled standalone above the latter stops inlining
entirely.

**R5. Every opcode must run during warm-up.** A case with no profile samples
compiles to an uncommon trap with `Action_reinterpret`
(`deoptimization.cpp:2333`): the first contract to use it invalidates the
compiled loop, resets the method's counters, and sends the interpreter back
through tier-3 profiling. Every rare opcode is one such cliff per process.

Two related notes. `@ForceInline` is silently ignored outside the boot and
platform class loaders (`classFileParser.cpp:1811`), so `--add-exports` buys
nothing at runtime; the supported levers are `-XX:CompileCommand=inline` and a
JEP 165 directives file. JDK 25's AOT cache stores method profiles, not compiled
code (JEP 515), so a training run addresses R5 but does not remove the compile.

## 4. What an opcode costs today

The loop hoists pc and gas into locals, but only for the ~60 percent of opcodes
flagged in `LOOP_OWNS_STATE`, and never for the stack pointer. Every operation
reads `stackTopV2` through an accessor two or three times and writes it back,
and the loop then reads three fields off the result.

| Opcode | Frame reads | Frame writes | Result reads | Allocations | Note |
|---|---|---|---|---|---|
| PUSH1-32 | 3 | 1 | 0 | 0 | inline since c8a5c95 |
| ADD | 3 | 1 | 3 | 3 | `UInt256` records |
| DUP1-16 | 3 | 1 | 3 | 0 | top read three times |
| SWAP1-16 | 2 | 0 | 3 | 0 | |
| POP | 2 | 1 | 3 | 0 | touches no array |
| MLOAD | 6 | 3 | 3 | 1 | memory size read twice |
| JUMP taken | 4 | 3 | 3 | 0 | three pc writes |
| SLOAD | 8 | 3 | 3 | 6 | two results thrown away |
| CALL | — | — | 3 | ~13 | new op object per call |

ADD allocates three `UInt256` records per execution, so its cost depends
entirely on escape analysis holding; nothing in the design guarantees it.
`StackArithmetic.add`, a 357-bytecode carry chain, has no callers anywhere.

### Waste that can go independently of any redesign

1. `SLoadOperationV2.staticOperation` allocates two `OperationResult` objects per
   execution and re-derives three gas costs the constructor already precomputed;
   the switch calls this path rather than the instance one (`EVM.java:851`).
2. CALL, CALLCODE, DELEGATECALL, STATICCALL, CREATE and CREATE2 each allocate a
   fresh operation object before doing any work, though the EVM already caches
   prebuilt instances for CHAINID and GAS (`CallOperationV2.java:110` and five
   siblings).
3. `MessageFrame.Builder` eagerly creates a `HashMultimap` for every child frame,
   which no child frame populates (`MessageFrame.java:1689`).
4. A taken jump writes pc three times (`JumpOperationV2.java:77`, then
   `EVM.java:1009` and `:1011`).
5. Memory gas costs read the memory size two ways per op, through two
   `Math.toIntExact` calls inside try/catch (`FrontierGasCalculator.java:397`).
6. `ensureV2Stack` allocates outside the pool, and `returnStackToPool` releases
   into it (`MessageFrame.java:561`).
7. `StackArithmetic.add` and `sub` are dead code.

## 5. How other implementations are built

| Implementation | Dispatch | Loop state | Per-op result | Tracing |
|---|---|---|---|---|
| evmone baseline | computed goto / switch | pc, stack top, gas in locals | null-pc sentinel | separate loop instance |
| revm | const fn-pointer table | all in the Interpreter struct | unit; halt sets a bool | separate loop function |
| Nethermind | fn-pointer array via `calli` | stack, gas, pc by ref | enum error code | generic param specialises the loop |
| go-ethereum | jump table of structs | pc local, rest in structs | bytes + error | `if debug` inside the loop |
| Espresso | switch in one bytecode node | bci, sp as locals | int | separate node |
| **Besu v2 today** | tableswitch, 91 targets | pc, gas for 60% of ops; stack top never | object, 3 fields read | boolean, since cf7077b |

Two techniques transfer directly:

- **revm**: constant gas is stored per opcode in the dispatch table and charged
  *before* the arm runs, so the common case never carries a cost back out.
- **go-ethereum**: minimum and maximum stack depth are table lookups checked once
  before the arm, removing bounds accessors from every operation body.

One does not. Nethermind specialises its whole loop on a generic type parameter,
so traced and untraced are separate machine code with no branch between them.
Java has no JIT-level equivalent; evmone's approach of instantiating a second
loop is the shape to copy if per-op tracing ever needs to be fast.

## 6. The proposed loop

**The loop owns its state.** pc, stack pointer, gas, code array and stack array
are locals for every opcode, not 60 percent of them, written back to the frame at
exactly two moments: before an out-of-line call that needs them, and at exit.

**Hot arms have bodies; cold arms have calls.** ~25 opcodes carry their
implementation inline in the switch, first in source order. Everything else is
one call into a second switch in its own method with its own budget, taking the
frame and returning an int.

**Constant gas and stack bounds come from tables before dispatch.** A
`byte[256]` of constant costs and a `short[256]` of packed stack requirements are
read once per iteration. The result protocol then disappears from the fast path:
an inlined arm mutates locals and continues, a cold call returns a status.

```java
// one iteration, sketch
int op = pc < code.length ? code[pc] & 0xff : STOP;

// R2: bounds and constant gas from tables, before the switch
int req = stackReq[op];                       // packed min | max
if (sp < (req & 0xff) || sp > (req >>> 8)) return haltStack(frame, pc, gas, sp, op);
if ((gas -= constGas[op]) < 0)               return haltGas(frame, pc, sp, op);

switch (op) {
  // ~25 hot arms, first in source order (R4), no calls out (R2)
  case 0x80: { // DUP1
    int s = (sp - 1) << 2, d = sp << 2;
    stack[d] = stack[s];     stack[d + 1] = stack[s + 1];
    stack[d + 2] = stack[s + 2]; stack[d + 3] = stack[s + 3];
    sp++; pc++; continue;                     // R3: no result object
  }
  ...
  default: {
    frame.setPC(pc); frame.setGasRemaining(gas); frame.setTopV2(sp);
    int status = cold(frame, op);             // second switch, own 8000 budget
    if (status != OK) return finish(frame, status);
    pc = frame.getPC(); gas = frame.getRemainingGas(); sp = frame.stackTopV2();
  }
}
```

Which opcodes are hot is the one input this note lacks. The static distribution
across WETH, USDT and the Uniswap V2 router is PUSH 23%, DUP 19%, SWAP 10%,
POP 7%, ADD 6%, JUMPDEST 4%, then MSTORE, ISZERO, JUMPI and AND at 3% each.
Dynamic frequency on mainnet blocks would be better, and the counting tracer
already in `evm/src/jmh/.../v2/PushBenchmark.java` can produce it. Measure before
fixing the order, because R4 makes the order load-bearing.

## 7. Staging

Each stage stands alone and has a check that does not depend on the node, whose
hour-to-hour variance is ~10 percent and cannot resolve anything smaller.

**Stage 0 — remove the waste in section 4.** No design change. Check: allocation
profile per block plus the JMH whole-program harness.

**Stage 1 — split the switch into hot and cold methods.** Hot arms keep calling
their current helpers; nothing else changes. This buys the budget headroom
everything later spends. Check: a unit test asserting both methods stay under
8000 bytecodes, plus `-XX:+PrintInlining` showing no
`size > DesiredMethodLimit`.

**Stage 2 — own the stack pointer, inline the hot arms, drop the result object.**
Removes two or three accessor round trips and three result field reads per hot
opcode. Check: the JMH harness; if the per-op figure does not move, the premise
is wrong and the stage reverts.

**Stage 3 — table-driven constant gas and stack bounds.** Check: the reference
tests are the correctness gate, since gas accounting moves.

**Stage 4 — execute every opcode during warm-up**, or use a JEP 515 training run.
Check: `-Xlog:deoptimization=debug` shows no `unstable_if` traps in the loop
after warm-up.

## 8. What this is worth, and what would sink it

On the engine thread the loop is 59 percent of the work inside `newPayload`, and
the loop's own dispatch and arithmetic are about 40 percent of that. Removing
half the per-op overhead it addresses moves block time by roughly 8 to 12
percent. Worth having; not a factor of two.

The honest risk is the one the last three designs demonstrated: the loop is not
where the remaining time is. Seventy-two percent of engine-thread `newPayload`
work is re-execution of transactions the speculative pass could not reuse, and
the same profile shows SLOAD spending two thirds of its time walking a chain of
nested updaters up to eighteen deep. Both are larger numbers than anything here.

So: take Stage 0 now, because it is pure waste. Take Stages 1 and 2 only if the
JMH harness shows the per-op figure moving, and stop if it does not. Expect the
bigger wins from the transaction-level work rather than from the interpreter.

## Sources

JDK 25 GA HotSpot: `compilationPolicy.cpp`, `bytecodeInfo.cpp`, `parse1.cpp`,
`parse2.cpp`, `escape.cpp`, `deoptimization.cpp`, `ifnode.cpp`,
`classFileParser.cpp`, `c2_globals.hpp`, `compiler_globals.hpp`,
`globals_x86.hpp`. Local checkouts of revm, go-ethereum and Nethermind. evmone
baseline notes and GraalVM Truffle host-compilation documentation. Besu figures
from `javap` on `EVM.class` and four JFR wall profiles taken on the mainnet node
between 12 and 15 September 2026; shares are sample-limited, treat as ±3 points.
