package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

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
 *     <li>normalized working copy — N*P of the element type</li>
 *     <li>one {@code tileSize^2} double scratch block per column-tile task —
 *     at most {@code (P/tileSize) * tileSize^2 * 8} transient bytes, negligible
 *     against N*P</li>
 * </ol>
 * <b>TOTAL = {@code NP + PP} elements held at once (plus the input)</b> — half the
 * bytes for the {@link FloatMatrix} variant, whose sums still accumulate in double.
 * <p>
 * Both phases parallelize across columns on the {@link java.util.concurrent.ForkJoinPool
 * common pool}, which sizes itself to the number of available cores. Inputs whose
 * estimated work is below {@link #PARALLEL_THRESHOLD_FLOPS} are processed on the
 * calling thread to avoid pool overhead dominating small problems.
 * <p>
 * The orchestration is shared between the double and float variants via
 * {@link Kernels}, which confines the element-type-specific inner loops.
 */
final class PearsonCorrelationCalculator implements CorrelationCalculator {

    /**
     * Roughly the amount of work (multiply-adds, ~ n*p*p) below which forking
     * to the common pool costs more than it saves.
     */
    static final long PARALLEL_THRESHOLD_FLOPS = 1L << 18;

    private final Kernels<double[]> doubleKernels;
    private final Kernels<float[]> floatKernels;

    /**
     * The {@link Profile} chooses the kernels; the orchestration here is the
     * same for every profile.
     */
    PearsonCorrelationCalculator(Kernels<double[]> doubleKernels, Kernels<float[]> floatKernels) {
        this.doubleKernels = doubleKernels;
        this.floatKernels = floatKernels;
    }

    @Override
    public DoubleMatrix calculate(DoubleMatrix observations) {
        int p = observations.cols();
        double[] corr = correlate(observations.data(), observations.rows(), p, doubleKernels);
        return DoubleMatrix.columnMajor(corr, p, p);
    }

    @Override
    public FloatMatrix calculate(FloatMatrix observations) {
        int p = observations.cols();
        float[] corr = correlate(observations.data(), observations.rows(), p, floatKernels);
        return FloatMatrix.columnMajor(corr, p, p);
    }

    /**
     * The shared two-phase engine: normalize every column, then fill the symmetric
     * correlation matrix with pairwise column dot products. {@code A} is the flat
     * column-major storage array, {@code double[]} or {@code float[]}.
     */
    private static <A> A correlate(A src, int n, int p, Kernels<A> kernels) {
        if (n < 1 || p < 1) {
            throw new InvalidInputException(
                    "At least one observation row and one variable column are required, got [" + n + "x" + p + "]");
        }
        boolean parallel = (long) n * p * p >= PARALLEL_THRESHOLD_FLOPS;

        A normalized = kernels.allocate(n * p);
        columns(p, parallel).forEach(col -> kernels.normalizeColumn(src, normalized, n, col));

        A corr = kernels.allocate(Math.multiplyExact(p, p));
        int tile = kernels.tileSize();
        int tileCount = (p + tile - 1) / tile;
        columns(tileCount, parallel).forEach(tileJ -> {
            // each tileJ writes a disjoint set of cells: the pairs whose larger
            // column index falls in this tile, mirrored across the diagonal —
            // safe to run concurrently
            int colJ0 = tileJ * tile;
            int countJ = Math.min(tile, p - colJ0);
            for (int jj = 0; jj < countJ; jj++) {
                int j = colJ0 + jj;
                kernels.set(corr, j * p + j, 1.0);
            }
            double[] dots = new double[tile * tile];
            for (int tileI = 0; tileI <= tileJ; tileI++) {
                if (tileI == tileJ && countJ == 1) {
                    continue; // a 1x1 diagonal tile would only compute the self-dot
                }
                int colI0 = tileI * tile;
                int countI = Math.min(tile, p - colI0);
                kernels.dotTile(normalized, n, colI0, countI, colJ0, countJ, dots);
                for (int jj = 0; jj < countJ; jj++) {
                    int j = colJ0 + jj;
                    int upToII = tileI == tileJ ? jj : countI;
                    for (int ii = 0; ii < upToII; ii++) {
                        int i = colI0 + ii;
                        double r = dots[ii * countJ + jj];
                        kernels.set(corr, j * p + i, r);
                        kernels.set(corr, i * p + j, r);
                    }
                }
            }
        });
        return corr;
    }

    private static IntStream columns(int p, boolean parallel) {
        IntStream range = IntStream.range(0, p);
        return parallel ? range.parallel() : range.sequential();
    }
}
