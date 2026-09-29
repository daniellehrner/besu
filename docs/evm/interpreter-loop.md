# The EVM interpreter loops

How `EVM.runToHalt` runs code, why it is built the way it is, and how to change
it without making it slower. Read this before changing the loops or anything
they run inline.

Status: `perf/evm-v2-poc`, 2026-09-29, JDK 25.

## Overview

Besu has two interpreters. The standard one keeps the operand stack as `Bytes`
items. EVM v2 (`--Xevm-v2`) keeps it as a `long[]`, four limbs per item. Each
interpreter has two loops, and `runToHalt` picks one per call from
`OperationTracer.isEnabled()`:

| Loop | Used for | State lives in |
|---|---|---|
| `runToHaltTraced`, `runToHaltV2Traced` | Tracing: `debug_trace*`, `trace_*` and other enabled tracers | the frame, where the tracer reads it |
| `runToHaltUntraced`, `runToHaltV2Untraced` | Everything else, block import included | loop locals |

The traced loops call the tracer around every operation and run each operation
through its implementation. The untraced loops have no tracer hooks. They run the
frequent operations inline in the loop and hand everything else to the same
implementations the traced loops use, so both loops behave the same.

Speed matters only in the untraced loops, and all of the design below is about
them.

## What HotSpot's C2 allows

The untraced loops are shaped around a few C2 limits, JDK 25 defaults. The two
8000-byte limits are fixed in a product build.

- **`HugeMethodLimit` = 8000 bytes of bytecode.** A method above it is never
  compiled, at any tier, and not on-stack replaced either. An oversized loop
  would stay in the interpreter.
- **`DesiredMethodLimit` = 8000.** C2 counts the loop's own bytecode plus
  everything it inlines into it, and stops inlining once the total reaches 8000.
  After that even trivial accessors become calls. C2 inlines in bytecode order,
  so the code that comes last loses out first.
- **`MaxInlineSize` = 35 bytes.** At a call site C2 does not rate hot, it inlines
  only callees up to this size. At a hot site the limit is `FreqInlineSize`, 325
  bytes.
- **Virtual and interface calls** inline only when the call site has seen one or
  two receiver classes.
- **A call that is not inlined** is opaque to C2. Values live across it are kept
  in memory, and fields are reloaded after it.
- **A branch that never ran during profiling** is compiled as a trap. The first
  time it runs, the compiled loop is thrown away and recompiled.

The measured state of the v2 untraced loop is:

| Measure | Value |
|---|---|
| Own bytecode | 4,949 bytes |
| Inlined by C2 (final compilation, benchmark mix) | 2,986 bytes |
| Total against `DesiredMethodLimit` | **7,935 of 8,000** |

About 2,000 of the inlined bytes are the JDK's VarHandle chain for the eight word
reads and writes of MLOAD, CALLDATALOAD and MSTORE. The budget is nearly used up:
another inline operation will likely push the last calls out of inlining, which
`EvmV2LoopCompilationTest` reports.

## How the untraced loops are built, and why

Each point below was measured. The numbers are from JMH (`EvmLoopBenchmark`) or
from the mainnet node, as noted.

1. **No tracer hooks.** A tracer may read the frame between any two operations,
   so with hooks in the loop no state can stay in registers. Once a third tracer
   class has reached the hooks, C2 compiles them as virtual calls: about 16% on a
   loop of cheap operations. One loop with an `if (tracing)` test instead of two
   loops costs 8–60%, because C2 then compiles every operation as if a tracer
   might follow.

2. **Loop state in locals.** The program counter, the remaining gas and the stack
   (the array and the top index) live in locals. They go back to the frame only
   before something that reads them there, which is the general path or a halt.
   Otherwise every operation reads and writes the frame's gas, pc and stack top.

3. **Frequent operations inline, success case only.** Called through its class,
   an operation costs a call, a result object and the reads of its fields; for
   JUMPDEST that is all its work.
   - An inline operation handles only the case where it succeeds. On too little
     stack or gas, or an unusual case, it changes nothing, and the loop runs the
     operation through its implementation. That implementation owns every halt,
     so halting behaviour is defined once for both loops.
   - The v2 untraced loop inlines nearly every operation mainnet executes. The
     standard one inlines about three quarters.

4. **No allocation and no call C2 does not inline, anywhere in the loop.** One
   such call in one case makes C2 keep every loop local in memory for the whole
   loop. A single `Bytes.wrap` in the standard loop's PUSH case took JUMPDEST
   from 0.45 to about 2 ns.

5. **Rare operations in a method of their own.** `coldOperation` holds the
   operations mainnet runs rarely, with its own 8000-byte budget. The switch runs
   on a dense index taken from a 256-entry table rather than on the opcode, since
   a switch over opcodes up to 0xe8 costs 943 bytes of bytecode by itself.

6. **Costs, steps and stack changes applied in one common tail.** Each inline
   case reports what it charges, how far it moves the program counter and how
   many items it adds. Written out in every case, the same update is one
   expression, which C2 moves ahead of the dispatch where it runs for every
   operation.

7. **A per-case bias on stack and code indices.** Indices built directly on the
   stack pointer or the program counter are the same in every case, and C2
   computes them all ahead of the dispatch, where they take the registers the
   loop state needs. With a bias that each case cancels, the indices differ
   between cases and stay inside them.

8. **Gas costs as `static final` constants.** As fields of the gas calculator
   they are loads that C2 hoists to the dispatch.

9. **No inner loops, not even over a word's four limbs.** Every loop head is an
   on-stack replacement entry point. Which compilation a JVM ends up with then
   depends on where it entered, and the same program ran at 2.7k or 16k µs from
   one JVM to the next.

10. **Code analysis tables read through `Code`, not held in locals.** Five more
    locals across the loop leave too few registers for its state.

11. **PUSH values from tables.**
    - v2 decodes every immediate once per contract, during code analysis, and
      the loop copies it. PUSH is the most frequent operation, and decoding it on
      every execution was a sixth of the loop's time: a PUSH/POP/JUMP mix went
      from 3.4 to 2.7 ns per operation.
    - The standard loop takes PUSH1 values from a shared table of 256, and
      PUSH2 values from a 64K table filled as values first run, so its PUSH does
      not allocate.

12. **Fused jumps.** JUMP and JUMPI check the destination bitmap directly and
    also run the JUMPDEST they land on. PUSH2 followed by JUMP or JUMPI runs as
    one step, without the destination going through the stack. On mainnet,
    JUMPDEST is 9.4% of operations and JUMP and JUMPI about 6% each.

13. **Standard loop only:** stack items are read behind an exact class check, so
    C2 reads fields instead of calling through the `Bytes` interface. The stack
    is an `Object[]` rather than a `Bytes[]`, which drops Java's array store
    check on every push, DUP and SWAP: 14–29% on stack-heavy programs.

14. **Tracer-only records only when tracing.** Memory and storage change records
    exist only for tracers and are made only when one is attached.

Mainnet's most frequent operations, counted over 400 blocks:

| DUP | SWAP | JUMPDEST | POP | JUMPI | JUMP | ADD | … | SLOAD |
|---|---|---|---|---|---|---|---|---|
| 23.1% | 14.2% | 9.4% | 7.1% | 6.1% | 6.0% | 5.6% | | 0.5% |

## The v2 inline code and its generator

The v2 untraced loop's inline cases are not written in `EVM.java`. Each is a
static method of its operation's class, marked `@InlineInEvmLoop`. For example,
`AddOperationV2.add` sits next to `AddOperationV2.staticOperation`, which calls
it.

`./gradlew :evm:generateEvmV2Loop` copies each method's body into the loop,
between `BEGIN automatically copied from AddOperationV2.add` and
`END automatically copied from AddOperationV2.add` comments. It orders the copies
by their lowest opcode.

The generated regions are checked in, so they can be read in review. Before
every compile of `evm`, the generator runs in check mode. It fails the build when
an inline method breaks one of the rules above, or when `EVM.java` no longer holds
exactly what the methods generate.

| What | Where |
|---|---|
| The inline code, one method per case | `evm/src/main/java/org/hyperledger/besu/evm/v2/operation/`, marked `@InlineInEvmLoop` |
| The rules, and what inline code may name | `InlineInEvmLoop` and `EvmLoopInlining`, same package |
| The loop and its two generated regions | `EVM.runToHaltV2Untraced` |
| The generator and rule checker | `evm/src/loopgen/java/org/hyperledger/besu/evm/loopgen/EvmV2LoopGenerator.java` |

**Operation classes share the inline code.**
- Where an inline method runs every successful case of its operation, the
  operation's `staticOperation` calls it. It passes `EvmLoopInlining.ANY_GAS`,
  since its callers charge the gas, and adds only the stack halts. That covers
  ADD, SUB, AND, OR, XOR, NOT, ISZERO, EQ, LT, GT, SLT, SGT, SHL, SHR, SAR,
  SIGNEXTEND, PUSH0, PUSH1-32, DUP, SWAP, POP, JUMPDEST and GAS.
- PUSH2, JUMP and JUMPI keep their own implementation, because their inline code
  also runs the operation after them. A tracer has to see that operation as a
  step of its own.
- MUL, DIV, MOD, MSTORE, MLOAD, CALLDATALOAD and CALLDATASIZE also keep their
  own, because their inline code handles only the common case.
- Operations that share inline code keep it in the class of the first of them:
  LT/GT/SLT/SGT in `LtOperationV2`, SHL/SHR/SAR in `ShlOperationV2`, MLOAD with
  CALLDATALOAD in `MloadOperationV2`, DIV with MOD in `DivOperationV2`. One case
  costs the loop less budget than several.
- The traced loop reaches `staticOperation` from a switch where C2 rates no call
  as hot, so it inlines them only up to 35 bytes. Each is written as its inline
  call plus `EvmLoopInlining.result`, 13–34 bytes. At 41–66 bytes they became
  calls and cost the traced loop 10–12% on DUP- and PUSH-heavy code.

**Changing or adding inline code:**
1. Edit or add the `@InlineInEvmLoop` method in the operation's class.
   - Opcodes are written in hex.
   - Parameters are loop state, taken by name: the list is on
     `InlineInEvmLoop`.
   - Constants and helpers are imported statically from `EvmLoopInlining`, as
     `EVM.java` imports them. The generator checks both, so that a name means the
     same in the loop as in the method.
2. Run `./gradlew :evm:generateEvmV2Loop`.
3. If the method runs every successful case, make `staticOperation` call it
   through `EvmLoopInlining.result`, as `AddOperationV2` does.
4. Add the operation to `EvmV2LoopCompilationTest.Workload.program()`. C2 reports
   nothing about code its workload never reaches.
5. Run `./gradlew :evm:test`, then measure.

Never edit the generated regions by hand; the build rejects it.

## What guards the loops

| Check | Runs | Catches |
|---|---|---|
| `checkEvmV2Loop` (the generator) | Before every compile of `evm` | Inline code breaking a rule; a hand edit of a generated region; an opcode not in hex; a name that would resolve differently in the loop than in its method |
| `EvmLoopMethodSizeTest` | `:evm:test` | Methods over 8000 bytes; a loop over its 6000-byte allowance; any call from the v2 untraced loop, three levels deep, over 35 bytes; a `staticOperation` that runs inline code but has grown over 35 bytes |
| `EvmV2LoopCompilationTest` | `:evm:test`, about 3 s | What C2 actually inlines into the v2 untraced loop, read from C2's own report in a child JVM. This includes the 8000-byte inlining budget no bytecode check can see |
| `InlineInEvmLoopTest` | `:evm:test` | Every arithmetic inline method against `BigInteger` |
| `InterpreterLoopV1DifferentialTest`, `InterpreterLoopV2DifferentialTest` | `:evm:test` | Each untraced loop against its traced loop, over edge-case and random programs, for eight forks |
| Reference tests | By hand: `./gradlew :ethereum:referencetests:referenceTests -Dtest.evm.v2=true` | Consensus on EVM v2 |

## Measuring a change

The checks enforce the traps that were measured. They do not measure speed, and
a change can pass them all and still slow the loop. Measure every change to the
untraced loops or their inline code:

```
./gradlew :evm:jmhJar
taskset -c 0-3,12-15 java -jar evm/build/libs/evm-*-jmh.jar EvmLoopBenchmark -p v2=true
```

- **Alternate the runs.** Build one jar from the base and one from the change,
  and run base, change, change, base. Drift then shows as a difference between
  the two base runs. The jar name includes the commit, so copy the newest.
- **Pin the performance cores** and check the load first. On a busy machine only
  differences above about 10% are real.
- **Read the results in Mgas/s** (gas used divided by time) as well as in
  percent. 100 Mgas/s is the floor; the synthetic loops run at 600–1,130 Mgas/s.
- **Programs to watch.** The synthetic programs (`JUMPDEST_SPAM`, `PUSH_POP`,
  `ARITH`, `MEMORY`) show a register spill first. `WETH_TRANSFER` and
  `USDT_BALANCE` show what contracts see.
- **Read the compiled code when a result is unexpected.** Put hsdis on
  `LD_LIBRARY_PATH` and add `-jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions
  -XX:CompileCommand=print,org.hyperledger.besu.evm.EVM::runToHaltV2Untraced"`.
  A spill shows as a `mov` of a loop variable to or from `[rsp+…]` inside a case.

## Tried and rejected

So that no one needs to try these again:

- **Other dispatch designs:** a table of per-opcode handlers, and a loop calling
  operation objects. They measured the same as the switch on the node. A call
  through operation objects sees dozens of receiver classes and is never
  inlined.
- **One loop for traced and untraced execution**, with the tracer behind a
  test: 8–60% slower.
- **A table-driven gas and stack prologue ahead of the dispatch**, and nesting
  the fast cases in an inner loop: neither improved register allocation.
- **A PUSH table indexed by program counter:** a few percent faster than the
  per-contract table, at eight times the code size.
- **Keeping the analysis tables in locals:** too few registers left.
- **Frequency order instead of opcode order for the inline cases:** 3–8% faster
  on the synthetic loops, no difference on contracts. Opcode order was kept
  because it is easier to review.
