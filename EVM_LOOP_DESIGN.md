# EVM loop redesign

Status: phase 1 implemented in the working tree (uncommitted). The "contract" sections below
describe the end state after phase 2; "Phases" says what phase 1 actually contains.

## Decisions taken

- Stack stays `OperandStack`. The `long[]` v2 stack is a separate, later change.
- Rollback keeps the three existing mechanisms (world-updater nesting, `TxValues` undo marks,
  hand-cleared frame fields). They are only *called from fewer places*.
- No benchmark harness up front.
- `MessageFrame` stays the frame type. Tracers and plugins reach it through
  `BlockAwareOperationTracer extends OperationTracer`, and the japicmp check does not cover
  `org.hyperledger.besu.evm.*`, so breaking it would be silent.
- Precompile calls keep a real frame and their `traceContextEnter`/`Exit` pair. `CallTracer`
  builds its tree from those hooks and `EthTransferLogOperationTracer` emits transfer logs from
  them.

## Goal

Two loops with an explicit contract between them:

- **Outer loop** owns the frame stack: start a frame, run it, finish it, hand its result to the
  parent. One implementation, replacing the four copies in `MainnetTransactionProcessor`,
  `SystemCallProcessor`, `EVMExecutor` and `EvmToolCommand`.
- **Inner loop** executes the opcodes of exactly one frame and *returns* why it stopped.

Today that contract is the mutable `MessageFrame.State` field (written from 11 sites, read on
every opcode) plus a capturing `completer` lambda per CALL/CREATE.

## The contract

```java
// exit codes of the inner loop
static final int EXIT_SUCCESS = 1;   // STOP / RETURN / SELFDESTRUCT / end of code
static final int EXIT_REVERT  = 2;
static final int EXIT_HALT    = 3;   // reason in frame.exceptionalHaltReason
static final int EXIT_CALL    = 4;   // child is in frame.pendingChild
```

### Outer loop (`EVM.execute(MessageFrame root, OperationTracer tracer)`)

```java
final boolean traced = tracer != OperationTracer.NO_TRACING;
MessageFrame frame = root;
start(frame, tracer);                                  // traceContextEnter + processor.start
while (true) {
  if (frame.getState() == CODE_EXECUTING) {            // false for precompiles and failed starts
    final int exit = traced ? runTraced(frame, tracer) : run(frame);
    if (exit == EXIT_CALL) {
      final MessageFrame child = frame.takePendingChild();
      frameStack.push(child);
      frame = child;
      start(frame, tracer);
      continue;
    }
    applyExit(frame, exit);                            // sets CODE_SUCCESS / REVERT / EXCEPTIONAL_HALT
  }
  finish(frame, tracer);                               // codeSuccess, halt/revert rollback, commit, traceContextExit
  frameStack.pop();
  if (frameStack.isEmpty()) return;
  final MessageFrame parent = frameStack.peek();
  deliver(parent, frame);                              // the old complete(): the only place results reach a parent
  tracer.traceContextReEnter(parent);
  frame = parent;
}
```

- `start` / `finish` are today's `AbstractMessageProcessor.process` split in two, still
  dispatching on `frame.getType()` to `MessageCallProcessor` / `ContractCreationProcessor`.
  `clearAccumulatedStateBesidesGasAndOutput`, `handleStateGasOnFrameFailure`, code deposit and
  the precompile path move unchanged.
- `deliver` calls `pendingOperation.complete(parent, child)`; the parent remembers which
  CALL/CREATE operation suspended it. No lambda. `complete()` keeps advancing the parent pc for
  now (see phase 2).
- The child is handed over through `frame.pendingChild`, not through a side effect of
  `MessageFrame.Builder.build()`. The deque becomes private to the outer loop; tracers that peek
  it for the child's remaining gas / code get a dedicated accessor.
- `ModificationNotAllowedException` is caught around `run`, mapped to `EXIT_REVERT` as today.

### `State` after the change

`State` stops being control flow for the hot path. The outer loop writes it at frame
boundaries only, which is all external readers need:

| Value | Written by | Read by |
|---|---|---|
| `CODE_EXECUTING` | `start` | outer loop |
| `CODE_SUSPENDED` | CALL/CREATE op when it sets `pendingChild` | `CallTracer` in `tracePostExecution` |
| `CODE_SUCCESS`, `REVERT`, `EXCEPTIONAL_HALT` | `applyExit`, `start`, precompile result | `finish`, `PrestateTracer` |
| `COMPLETED_SUCCESS`, `COMPLETED_FAILED` | `finish` | tracers, tx processor, `complete()` |

The inner loop never reads it.

### Inner loop (`run`, untraced)

```java
int run(final MessageFrame frame) {
  final byte[] code = ...; final Operation[] ops = ...;
  int pc = frame.getPC();
  try {
    while (true) {
      final int opcode = pc < code.length ? code[pc] & 0xff : 0;
      final OperationResult result = switch (opcode) {
        ...hot static arms, PUSH/DUPN take the local pc...
        case 0x56, 0x57 -> { frame.setPC(pc); yield Jump*.staticOperation(frame); }
        default -> { frame.setPC(pc); yield ops[opcode].execute(frame, this); }
      };
      if (result.getHaltReason() != null)            return halt(frame, pc, result.getHaltReason());
      if (frame.decrementRemainingGas(result.getGasCost()) < 0) return halt(frame, pc, INSUFFICIENT_GAS);
      final int exit = frame.pendingExit();          // 0 unless STOP/RETURN/REVERT/SELFDESTRUCT/CALL set it
      if (exit != 0) { frame.setPC(pc); return exit; }
      pc = (opcode == 0x56 || opcode == 0x57) ? frame.getPC() + result.getPcIncrement()
                                              : pc + result.getPcIncrement();
    }
  } catch (OverflowException | UnderflowException e) { ...halt... }
}
```

- The try/catch moves outside the `while`, so the loop body is straight-line.
- `pc` is a local; it is written to the frame only before arms that may read it and on exit.
- Terminating ops set `frame.pendingExit` instead of `setState`. This is the one new field the
  ops write; `StopOperation`, `ReturnOperation`, `RevertOperation`, `SelfDestructOperation`,
  `AbstractCallOperation`, `AbstractCreateOperation` are the only writers.
- `enableOptimizedOpcodes()` and the fork booleans are resolved once per `EVM` instance into
  final fields (or into which `run` variant is used), not per opcode.
- `setCurrentOperation` and the tracer calls exist only in `runTraced`.

`runTraced` is the same loop with `setCurrentOperation`, `tracePreExecution`, `setPC` every
step, `State` kept current (`CODE_SUSPENDED` before `tracePostExecution`), and
`tracePostExecution(frame, result)`. Both loops share every operation implementation.

## Phases

1. **Loop structure** (done). The operation contract is frozen, and operations own more control
   flow than the end state allows: `PushOperation` and `JumpService` write `frame.pc`, terminating
   ops and CALL/CREATE signal through `setState`, children reach the stack through
   `MessageFrame.Builder.build()`, and the completer lambda carries per-call data
   (`targetExists`). So in phase 1 `State` is still the exit signal of the inner loop and the
   child hand-off is unchanged. What changed:
   - `MessageFrameRunner` is the single outer loop. `MainnetTransactionProcessor.runFrames`,
     `SystemCallProcessor`, `EVMExecutor` and `EvmToolCommand` all use it. A side effect:
     system calls now dispatch by frame type instead of sending every frame to the
     message-call processor.
   - `AbstractMessageProcessor.process` is split into `begin` / `execute` / `finish`.
     They are private; `process` stays the public single-step entry point, called by the runner
     and by tests that step or mock frames.
   - `EVM.runToHalt` dispatches to `runUntraced` or `runTraced`. The untraced loop has no tracer
     calls, no `setCurrentOperation`, reads `pc` once per opcode, and has its try/catch outside
     the `while`. `runTraced` is the previous loop. `enableOptimizedOpcodes` is read once per
     run instead of per opcode, and the lambda-based `shiftOperation` helper is gone.
2. **Operation contract**, together with the v2 stack since both rewrite every operation: `int`
   status instead of `OperationResult`, static gas and stack bounds from per-fork tables
   charged before execute, pc owned by the loop (removes `pcIncrement`, the pc writes in
   PUSH/JUMP, the decrement/increment gas dance in CALL/CREATE and the pc bump in `complete()`),
   exit codes and `pendingChild` instead of `State` and the builder push, completer lambda
   replaced, traced loop fills one reusable `OperationResult` for tracers.
3. **Allocation**: frame pooling by depth, lazy stack/memory/logs/refunds, precompile frames
   without stack or memory.
4. **Rollback**: single journal checkpoint.

## Open points

- `MainnetTransactionProcessor.process(frame, tracer)` has no callers left in main sources;
  remove it or keep it for library users.
- `PrestateTracer` calls `createOp.cost(frame, …)` with a live frame; gas functions must stay
  callable from outside the loop (matters in phase 2).
