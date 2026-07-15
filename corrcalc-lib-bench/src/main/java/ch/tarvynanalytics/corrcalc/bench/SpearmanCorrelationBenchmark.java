package ch.tarvynanalytics.corrcalc.bench;

import ch.tarvynanalytics.corrcalc.lib.correlation.CorrelationCalculator;
import ch.tarvynanalytics.corrcalc.lib.correlation.Correlations;
import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
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
 * Spearman correlation across both storage types and all profiles.
 *
 * <p>Spearman adds an {@code O(n log n)} per-column ranking pass in front of
 * Pearson, plus a working copy of the ranked matrix. The ranking cost grows with
 * the row count, so the long sizes ({@code 100000x100}) show the widest gap to
 * {@link PearsonCorrelationBenchmark}, while wide-but-short inputs stay closer.
 * Data is seeded Gaussian noise (essentially tie-free).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Xmx2g", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class SpearmanCorrelationBenchmark {

    @Param({"1000x10", "10000x100", "100000x100", "10000x1000"})
    private String size;

    @Param({"STANDARD"})
    private String profile;

    private CorrelationCalculator calculator;
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
        calculator = Correlations.spearman(Profile.valueOf(profile));
    }

    @Benchmark
    public DoubleMatrix spearmanDouble() {
        return calculator.calculate(doubleMatrix);
    }

    @Benchmark
    public FloatMatrix spearmanFloat() {
        return calculator.calculate(floatMatrix);
    }
}
