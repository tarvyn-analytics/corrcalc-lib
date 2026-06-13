package ch.tarvynanalytics.corrcalc.bench;

import ch.tarvynanalytics.corrcalc.lib.correlation.CorrelationCalculator;
import ch.tarvynanalytics.corrcalc.lib.correlation.Correlations;
import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ojalgo.matrix.store.MatrixStore;
import org.ojalgo.matrix.store.Primitive64Store;
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
 * Competitive benchmark: corrcalc-lib's Pearson correlation against the
 * best-performing pure-Java rivals.
 *
 * <ul>
 *   <li><b>corrcalc STANDARD</b> — portable scalar, parallel</li>
 *   <li><b>corrcalc VECTORIZED</b> — SIMD+FMA, parallel</li>
 *   <li><b>Apache Commons Math</b> — {@code PearsonsCorrelation}, the
 *       conventional baseline (single-threaded scalar)</li>
 *   <li><b>EJML</b> — {@code CommonOps_DDRM.multInner} for the Gram matrix, a
 *       block-optimized pure-Java matmul (single-threaded)</li>
 *   <li><b>ojAlgo</b> — {@code transpose().multiply()}, a multi-threaded
 *       pure-Java matmul</li>
 * </ul>
 *
 * <p>All compute the full {@code p x p} Pearson correlation matrix in double
 * precision from the same seeded Gaussian data. The timed call is the
 * correlation computation only; the input representations are built once in
 * {@link #setUp()}. EJML and ojAlgo have no direct correlation API, so they get
 * the idiomatic route a user would write: centre the columns, form
 * {@code Xc^T·Xc} with the library's matmul (the {@code O(n·p^2)} heavy part),
 * then normalize by the diagonal.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-Xmx4g", "--add-modules", "jdk.incubator.vector"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class CompetitiveBenchmark {

    @Param({"1000x10", "10000x100", "100000x100", "10000x1000"})
    private String size;

    private int rows;
    private int cols;
    private CorrelationCalculator corrcalcStandard;
    private CorrelationCalculator corrcalcVectorized;
    private DoubleMatrix doubleMatrix;
    private double[][] rowData;
    private PearsonsCorrelation pearsonsCorrelation;
    private DMatrixRMaj ejmlData;
    private Primitive64Store ojalgoData;

    @Setup
    public void setUp() {
        String[] parts = size.split("x");
        rows = Integer.parseInt(parts[0]);
        cols = Integer.parseInt(parts[1]);
        Random random = new Random(42L);
        rowData = new double[rows][cols];
        double[] columnMajor = new double[rows * cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double value = random.nextGaussian();
                rowData[i][j] = value;
                columnMajor[j * rows + i] = value;
            }
        }
        doubleMatrix = DoubleMatrix.columnMajor(columnMajor, rows, cols);
        corrcalcStandard = Correlations.pearson(Profile.STANDARD);
        corrcalcVectorized = Correlations.pearson(Profile.VECTORIZED);
        pearsonsCorrelation = new PearsonsCorrelation();
        ejmlData = new DMatrixRMaj(rowData);
        ojalgoData = Primitive64Store.FACTORY.rows(rowData);
    }

    @Benchmark
    public DoubleMatrix corrcalcStandard() {
        return corrcalcStandard.calculate(doubleMatrix);
    }

    @Benchmark
    public DoubleMatrix corrcalcVectorized() {
        return corrcalcVectorized.calculate(doubleMatrix);
    }

    @Benchmark
    public RealMatrix commonsMath() {
        return pearsonsCorrelation.computeCorrelationMatrix(rowData);
    }

    @Benchmark
    public DMatrixRMaj ejml() {
        int n = rows;
        int p = cols;
        DMatrixRMaj centered = new DMatrixRMaj(n, p);
        for (int j = 0; j < p; j++) {
            double mean = 0;
            for (int i = 0; i < n; i++) {
                mean += ejmlData.unsafe_get(i, j);
            }
            mean /= n;
            for (int i = 0; i < n; i++) {
                centered.unsafe_set(i, j, ejmlData.unsafe_get(i, j) - mean);
            }
        }
        DMatrixRMaj gram = new DMatrixRMaj(p, p);
        CommonOps_DDRM.multInner(centered, gram); // gram = centered^T * centered
        DMatrixRMaj corr = new DMatrixRMaj(p, p);
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                corr.unsafe_set(i, j,
                        gram.unsafe_get(i, j) / Math.sqrt(gram.unsafe_get(i, i) * gram.unsafe_get(j, j)));
            }
        }
        return corr;
    }

    @Benchmark
    public Primitive64Store ojalgo() {
        int n = rows;
        int p = cols;
        Primitive64Store centered = Primitive64Store.FACTORY.make(n, p);
        for (int j = 0; j < p; j++) {
            double mean = 0;
            for (int i = 0; i < n; i++) {
                mean += ojalgoData.doubleValue(i, j);
            }
            mean /= n;
            for (int i = 0; i < n; i++) {
                centered.set(i, j, ojalgoData.doubleValue(i, j) - mean);
            }
        }
        MatrixStore<Double> gram = centered.transpose().multiply(centered); // p x p, multi-threaded
        Primitive64Store corr = Primitive64Store.FACTORY.make(p, p);
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                corr.set(i, j,
                        gram.doubleValue(i, j) / Math.sqrt(gram.doubleValue(i, i) * gram.doubleValue(j, j)));
            }
        }
        return corr;
    }
}
