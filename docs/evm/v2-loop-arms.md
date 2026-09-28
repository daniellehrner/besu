# The untraced EVM v2 loop: arms, generator and checks

Status: implemented on `perf/evm-v2-poc`, 2026-09-28. Read this before changing
`EVM.runToHaltV2Untraced` or anything it runs inline.

`c2-loop-design.md` explains why the loop is shaped the way it is. This note is
the working guide: where the code is, how to change it, and what checks a change
has to pass.

## Why it is generated

The loop runs the cheap operations inline, as arms of one switch. It is fast only
while C2 keeps the loop's state (the program counter, the gas, the stack pointer)
in registers. Several ordinary-looking edits break that and cost every operation
a large share of its time:

- a call that is not inlined,
- an allocation,
- an expression that more than one arm shares,
- a small loop.

Code review does not reliably catch these, and nothing fails when they slip in.

The rules therefore live in code:

- Each arm is written once, as a method of its operation's class marked
  `@Arm`, next to the code the class runs when the arm does not. The javadoc of
  `Arm` gives each rule with the measurement behind it.
- A generator copies the methods' bodies into the loop.
- The generator rejects an arm that breaks a rule.
- The build fails when the loop no longer holds what the arms generate.

## Where things are

| What | Where |
|---|---|
| The arms, one `@Arm` method each, in the operation classes | `evm/src/main/java/org/hyperledger/besu/evm/v2/operation/`, e.g. `AddOperationV2.add` |
| The rules, and what arms may name | `Arm` and `Arms` in the same package |
| The loop, with two generated regions | `EVM.runToHaltV2Untraced`, between `BEGIN GENERATED` and `END GENERATED` |
| Generator and rule checker | `evm/src/loopgen/java/org/hyperledger/besu/evm/loopgen/EvmV2LoopGenerator.java` |

Generated code covers only the arm table and the arms. The rest of the loop is
written by hand:
- the dispatch with its per-arm bias,
- the common tail that applies an arm's cost, step and delta,
- the general path through `executeOperationV2`.

## Arms and operation classes

An arm handles only the case in which its operation succeeds. Anything else
returns `FALLBACK`, and the loop hands the operation to its class.

Where an arm runs every successful case, the class calls the arm too, so the
loop and the class share one implementation. The class passes `Arms.ANY_GAS`,
because its callers charge the gas, and adds only the stack halts. This applies to:

- ADD, SUB, AND, OR, XOR, NOT, ISZERO, EQ, LT, GT, SLT, SGT,
- SHL, SHR, SAR, SIGNEXTEND,
- PUSH0, PUSH1-32, DUP1-16, SWAP1-16, POP, JUMPDEST, GAS.

The other classes keep an implementation of their own:

- **PUSH2, JUMP and JUMPI.** Their arms also run the operation that follows them
  where they can. A tracer has to see that operation as a step of its own, and
  the traced loop runs the classes.
- **MUL, DIV, MOD, MSTORE, MLOAD, CALLDATALOAD and CALLDATASIZE.** Their arms run
  only the common case: small operands, memory already expanded, input data held
  as an array.

Operations that share an arm keep it in the class of the first of them, and the
others call it there: LT, GT, SLT and SGT in `LtOperationV2`, SHL, SHR and SAR in
`ShlOperationV2`, MLOAD with CALLDATALOAD in `MloadOperationV2`, DIV with MOD in
`DivOperationV2`. One arm costs the loop less of its budget than several.

## Changing or adding an arm

1. Edit or add the `@Arm` method in the operation's class.
   - A new arm names its opcodes, `@Arm(rank = …, opcodes = …)` or a range with
     `first` and `last`.
   - Its `rank` is its place in the loop, by how often mainnet executes it. C2
     inlines in the loop's order, and that order decides what fits the budget.
     The ranks run 1 to N without gaps, so a new arm shifts the ones after it.
   - Declare as parameters only the loop state the arm uses, by name. The list is
     in the javadoc of `Arm`.
   - Import the constants and helpers it names statically from `Arms`, as
     `EVM.java` does. The generator checks both, so that a name means the same in
     the loop as in the arm.
2. Run `./gradlew :evm:generateEvmV2Loop`. It rewrites the generated regions of
   `EVM.java` and formats them.
3. If the arm runs every successful case of its operation, make the operation's
   class call it through `Arms.result`, as `AddOperationV2` does.
   - Keep that method within 35 bytes of bytecode. The traced loop reaches it
     from a switch where C2 inlines nothing larger, and a call per operation
     there cost DUP- and PUSH-heavy code 10 percent.
4. Add the operation to `EvmV2LoopCompilationTest.Workload.program()`. C2 compiles
   an arm the workload never reaches as a trap, and reports nothing about the
   calls inside it.
5. Run `./gradlew :evm:test`, then measure (below).

Never edit the generated regions by hand. The build rejects that too.

## What guards the loop

| Check | Runs | Catches |
|---|---|---|
| `checkEvmV2Loop`, the generator in check mode | Before every compile of `evm` | An arm breaking a rule; a hand edit of a generated region; ranks out of order; a name an arm uses that does not come from `Arms` in its class or in `EVM.java`, or that a member of either hides, since Java would then resolve it differently in the loop than in the arm |
| `EvmLoopMethodSizeTest` | `:evm:test` | The loop's bytecode size; any call within three levels that is above `MaxInlineSize` (35 bytes); an operation class that runs through its arm but has grown past 35 bytes itself |
| `EvmV2LoopCompilationTest` | `:evm:test`, about 3 s | What C2 actually inlined into the loop, read from C2's own report in a child JVM. This includes the 8000-byte budget the bytecode checks cannot see. If the loop outgrows `HugeMethodLimit`, C2 does not compile it and the report is empty |
| `ArmsTest` | `:evm:test` | Every arithmetic arm against `BigInteger`, over edge-case and random operands |
| `InterpreterLoopV2DifferentialTest` | `:evm:test` | The untraced loop against the traced loop, over edge-case and random programs, for eight forks |
| Reference tests | By hand: `./gradlew :ethereum:referencetests:referenceTests -Dtest.evm.v2=true` | Consensus |

## What the checks cannot see

The checks enforce the traps that have been measured. They do not measure
performance.

A change can pass every check and still slow the loop, for example by changing
what C2 decides to hoist or spill. Measure any change to the arms or to the loop
around them:

```
./gradlew :evm:jmhJar
taskset -c 0-3,12-15 java -jar evm/build/libs/evm-*-jmh.jar EvmLoopBenchmark -p v2=true
```

- **Alternate the runs.** Build one jar from the base and one from the change,
  then run them base, change, change, base, so that drift shows up as a
  difference between the two base runs.
- **Pin the cores.** The `taskset` range above is the performance cores of a
  hybrid CPU. On a busy machine, only differences above about 10 percent are
  real.
- **Programs to watch.** The synthetic programs (`JUMPDEST_SPAM`, `PUSH_POP`,
  `ARITH`, `MEMORY`) show a register spill first. `WETH_TRANSFER` and
  `USDT_BALANCE` show what mainnet contracts see.
- **Read the compiled loop when a result is unexpected.** Put hsdis on
  `LD_LIBRARY_PATH` and add
  `-jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=print,org.hyperledger.besu.evm.EVM::runToHaltV2Untraced"`.
  A spill shows up as a `mov` of a loop variable to or from `[rsp+…]` inside an
  arm.
