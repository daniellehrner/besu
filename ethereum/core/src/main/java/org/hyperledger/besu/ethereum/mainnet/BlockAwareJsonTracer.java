package org.hyperledger.besu.ethereum.mainnet;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

public class BlockAwareJsonTracer implements BlockAwareOperationTracer {
  private final StringWriter stringWriter;
  private final PrintWriter printWriter;
  private final StandardJsonTracer tracer;

  public BlockAwareJsonTracer() {
    this.stringWriter = new StringWriter();
    this.printWriter = new PrintWriter(stringWriter);

    this.tracer = new StandardJsonTracer(this.printWriter, false, true, false, false);
  }

  @Override
  public void tracePreExecution(final MessageFrame frame) {
    tracer.tracePreExecution(frame);
  }

  @Override
  public void tracePostExecution(final MessageFrame frame, final Operation.OperationResult operationResult) {
    tracer.tracePostExecution(frame, operationResult);
  }

  @Override
  public void tracePrecompileCall(final MessageFrame frame, final long gasRequirement, final Bytes output) {
    tracer.tracePrecompileCall(frame, gasRequirement, output);
  }

  @Override
  public void traceAccountCreationResult(final MessageFrame frame, final Optional<ExceptionalHaltReason> haltReason) {
    tracer.traceAccountCreationResult(frame, haltReason);
  }

  @Override
  public void tracePrepareTransaction(final WorldView worldView, final Transaction transaction) {
    tracer.tracePrepareTransaction(worldView, transaction);
  }

  @Override
  public void traceStartTransaction(final WorldView worldView, final Transaction transaction) {
    tracer.traceStartTransaction(worldView, transaction);
  }

  @Override
  public void traceBeforeRewardTransaction(final WorldView worldView, final Transaction tx, final Wei miningReward) {
    tracer.traceBeforeRewardTransaction(worldView, tx, miningReward);
  }

  @Override
  public void traceEndTransaction(final WorldView worldView, final Transaction tx, final boolean status,
                                  final Bytes output, final List<Log> logs, final long gasUsed,
                                  final Set<Address> selfDestructs, final long timeNs) {
    tracer.traceEndTransaction(worldView, tx, status, output, logs, gasUsed, selfDestructs,
      timeNs);
  }

  @Override
  public void traceContextEnter(final MessageFrame frame) {
    tracer.traceContextEnter(frame);
  }

  @Override
  public void traceContextReEnter(final MessageFrame frame) {
    tracer.traceContextReEnter(frame);
  }

  @Override
  public void traceContextExit(final MessageFrame frame) {
    tracer.traceContextExit(frame);
  }

  @Override
  public void traceEndBlock(final BlockHeader blockHeader, final BlockBody blockBody) {
    printWriter.flush();
    System.out.println(
      "\n==== JSON Trace for Block "
        + blockHeader.getNumber()
        + " ("
        + blockHeader.getBlockHash()
        + ") ====");
    System.out.println(stringWriter);
  }
}
