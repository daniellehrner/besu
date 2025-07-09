package org.hyperledger.besu.ethereum.vm.operations;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@OutputTimeUnit(value = TimeUnit.NANOSECONDS)
public class BigIntegerExpBenchmark {

  protected static final int SAMPLE_SIZE = 30_000;
  static final BigInteger MOD_BASE = BigInteger.TWO.pow(256);

  protected BigInteger[] aPool;
  protected BigInteger[] bPool;
  protected int index;

  @Setup(Level.Trial)
  public void setUp() {
    aPool = new BigInteger[SAMPLE_SIZE];
    bPool = new BigInteger[SAMPLE_SIZE];
    RandomInputGenerator.fillPoolsBigInteger(aPool, bPool);
    index = 0;
  }

  @Benchmark
  public void benchmark() {
    final int i = index;
    index = (index + 1) % SAMPLE_SIZE;

    final BigInteger ignored = aPool[i].modPow(bPool[i], MOD_BASE);
  }
}
