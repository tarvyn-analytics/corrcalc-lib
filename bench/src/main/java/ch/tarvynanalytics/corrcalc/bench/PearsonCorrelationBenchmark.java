package ch.tarvynanalytics.corrcalc.bench;

import ch.tarvynanalytics.corrcalc.lib.correlation.Correlations;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Pearson correlation across both storage types and both execution paths.
 *
 * <p>Sizes are chosen to cover the regimes that behave differently:
 * {@code 1000x10} stays under the {@code n*p^2 >= 2^18} threshold (serial path),
 * {@code 10000x100} is a mid-size parallel run, {@code 100000x100} is long and
 * narrow (bandwidth-bound, where float should pull ahead), and {@code 10000x1000}
 * is dominated by the {@code p^2} dot-product phase.
 *
 * <p>Data is seeded Gaussian noise generated once per trial; the matrices are
 * never mutated by the calculator, so reuse across iterations is safe.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = "-Xmx2g")
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class PearsonCorrelationBenchmark {

    @Param({"1000x10", "10000x100", "100000x100", "10000x1000"})
    private String size;

    private DoubleMatrix doubleMatrix;
    private FloatMatrix floatMatrix;

    @Setup
    public void setUp() {
        String[] parts = size.split("x");
        int rows = Integer.parseInt(parts[0]);
        int cols = Integer.parseInt(parts[1]);
        Random random = new Random(42L);
        double[] doubleData = new double[rows * cols];
        float[] floatData = new float[rows * cols];
        for (int i = 0; i < doubleData.length; i++) {
            doubleData[i] = random.nextGaussian();
            floatData[i] = (float) doubleData[i];
        }
        doubleMatrix = DoubleMatrix.columnMajor(doubleData, rows, cols);
        floatMatrix = FloatMatrix.columnMajor(floatData, rows, cols);
    }

    @Benchmark
    public DoubleMatrix pearsonDouble() {
        return Correlations.pearson().calculate(doubleMatrix);
    }

    @Benchmark
    public FloatMatrix pearsonFloat() {
        return Correlations.pearson().calculate(floatMatrix);
    }
}
