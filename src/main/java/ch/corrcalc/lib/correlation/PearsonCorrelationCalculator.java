package ch.corrcalc.lib.correlation;

import ch.corrcalc.lib.exception.InvalidInputException;
import ch.corrcalc.lib.matrix.Matrix;

import java.util.stream.IntStream;

/**
 * Calculates the Pearson correlation matrix in two passes over a single
 * working buffer, trying to use as little memory and data movement as possible.
 * <p>
 * Algorithm: each column is centered and scaled by the inverse of its centered
 * norm {@code 1/sqrt(sum((x - mean)^2))}, after which every coefficient is a plain
 * dot product of two normalized columns — the {@code (n-1)} factors of the sample
 * covariance and standard deviations cancel out exactly. Columns with zero variance
 * yield {@code NaN} against every other column (the coefficient is undefined),
 * while the diagonal stays {@code 1}.
 * <p>
 * Memory consumption on top of the input and the output:
 * <ol>
 *     <li>normalized working copy — N*P of double</li>
 * </ol>
 * <b>TOTAL = {@code NP + PP} of double held at once (plus the input)</b>
 * <p>
 * Both phases parallelize across columns on the {@link java.util.concurrent.ForkJoinPool
 * common pool}, which sizes itself to the number of available cores. Inputs whose
 * estimated work is below {@link #PARALLEL_THRESHOLD_FLOPS} are processed on the
 * calling thread to avoid pool overhead dominating small problems.
 */
final class PearsonCorrelationCalculator implements CorrelationCalculator {

    /**
     * Roughly the amount of work (multiply-adds, ~ n*p*p) below which forking
     * to the common pool costs more than it saves.
     */
    static final long PARALLEL_THRESHOLD_FLOPS = 1L << 18;

    @Override
    public Matrix calculate(Matrix observations) {
        int n = observations.rows();
        int p = observations.cols();
        if (n < 1 || p < 1) {
            throw new InvalidInputException(
                    "At least one observation row and one variable column are required, got [" + n + "x" + p + "]");
        }

        boolean parallel = (long) n * p * p >= PARALLEL_THRESHOLD_FLOPS;
        double[] normalized = normalizeColumns(observations.data(), n, p, parallel);

        double[] corr = new double[Math.multiplyExact(p, p)];
        IntStream columns = IntStream.range(0, p);
        if (parallel) {
            columns = columns.parallel();
        }
        columns.forEach(varJ -> correlateColumnAgainstPredecessors(normalized, corr, n, p, varJ));
        return Matrix.columnMajor(corr, p, p);
    }

    /**
     * Returns a copy of the input where every column is centered and divided by its
     * centered norm. A zero-variance column is filled with NaN so each pairwise dot
     * product against it propagates NaN naturally.
     */
    private static double[] normalizeColumns(double[] src, int n, int p, boolean parallel) {
        double[] normalized = new double[n * p];
        IntStream columns = IntStream.range(0, p);
        if (parallel) {
            columns = columns.parallel();
        }
        columns.forEach(col -> {
            int offset = col * n;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += src[offset + i];
            }
            double mean = sum / n;

            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double centered = src[offset + i] - mean;
                normalized[offset + i] = centered;
                sumSq += centered * centered;
            }

            if (sumSq == 0) {
                // zero variance: the correlation coefficient is undefined
                for (int i = 0; i < n; i++) {
                    normalized[offset + i] = Double.NaN;
                }
            } else {
                double invNorm = 1.0 / Math.sqrt(sumSq);
                for (int i = 0; i < n; i++) {
                    normalized[offset + i] *= invNorm;
                }
            }
        });
        return normalized;
    }

    /**
     * Fills row {@code varJ} of the correlation matrix up to the diagonal — the dot
     * products of column {@code varJ} with every earlier column — and mirrors the
     * values across the diagonal. Each invocation writes a disjoint set of cells,
     * so invocations for different columns can run concurrently.
     */
    private static void correlateColumnAgainstPredecessors(double[] normalized, double[] corr, int n, int p, int varJ) {
        corr[varJ * p + varJ] = 1.0;
        int offsetJ = varJ * n;
        for (int varI = 0; varI < varJ; varI++) {
            int offsetI = varI * n;
            double r = 0;
            for (int row = 0; row < n; row++) {
                r += normalized[offsetI + row] * normalized[offsetJ + row];
            }
            corr[varJ * p + varI] = r;
            corr[varI * p + varJ] = r;
        }
    }
}
