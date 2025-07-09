package org.hyperledger.besu.ethereum.vm.operations;

import java.util.concurrent.TimeUnit;

import org.hyperledger.besu.evm.word256.Word256;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@OutputTimeUnit(value = TimeUnit.NANOSECONDS)
public class Word256AddBenchmark {

  protected static final int SAMPLE_SIZE = 30_000;

  protected Word256[] aPool;
  protected Word256[] bPool;
  protected int index;

  @Setup(Level.Trial)
  public void setUp() {
    aPool = new Word256[SAMPLE_SIZE];
    bPool = new Word256[SAMPLE_SIZE];
    RandomInputGenerator.fillPoolsWord256(aPool, bPool);
    index = 0;
  }

  @Benchmark
  public void benchmark() {
    final int i = index;
    index = (index + 1) % SAMPLE_SIZE;

    aPool[i].add(bPool[i]);
  }
}
