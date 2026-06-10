package ch.corrcalc.lib.correlation;

import ch.corrcalc.lib.exception.InvalidInputException;
import ch.corrcalc.lib.matrix.FloatMatrix;
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
 *     <li>normalized working copy — N*P of the element type</li>
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

    private static final Kernels<double[]> DOUBLE_KERNELS = new DoubleKernels();
    private static final Kernels<float[]> FLOAT_KERNELS = new FloatKernels();

    @Override
    public Matrix calculate(Matrix observations) {
        int p = observations.cols();
        double[] corr = correlate(observations.data(), observations.rows(), p, DOUBLE_KERNELS);
        return Matrix.columnMajor(corr, p, p);
    }

    @Override
    public FloatMatrix calculate(FloatMatrix observations) {
        int p = observations.cols();
        float[] corr = correlate(observations.data(), observations.rows(), p, FLOAT_KERNELS);
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
        columns(p, parallel).forEach(varJ -> {
            // each varJ writes a disjoint set of cells: the pairs in which it is
            // the larger index, mirrored across the diagonal — safe to run concurrently
            kernels.set(corr, varJ * p + varJ, 1.0);
            int offsetJ = varJ * n;
            for (int varI = 0; varI < varJ; varI++) {
                double r = kernels.dot(normalized, varI * n, offsetJ, n);
                kernels.set(corr, varJ * p + varI, r);
                kernels.set(corr, varI * p + varJ, r);
            }
        });
        return corr;
    }

    private static IntStream columns(int p, boolean parallel) {
        IntStream range = IntStream.range(0, p);
        return parallel ? range.parallel() : range.sequential();
    }

    /**
     * The element-type-specific inner loops of the engine. All means, sums of
     * squares and dot products accumulate in double regardless of the storage type;
     * only loads and stores touch the element type.
     */
    private interface Kernels<A> {

        A allocate(int length);

        /**
         * Centers column {@code col} of {@code src} and scales it by the inverse of
         * its centered norm, writing into the same column of {@code dst}. A
         * zero-variance column is filled with NaN so each pairwise dot product
         * against it propagates NaN naturally.
         */
        void normalizeColumn(A src, A dst, int n, int col);

        /**
         * Dot product of the {@code n} elements at {@code offsetI} and {@code offsetJ}.
         */
        double dot(A data, int offsetI, int offsetJ, int n);

        void set(A data, int index, double value);
    }

    private static final class DoubleKernels implements Kernels<double[]> {

        @Override
        public double[] allocate(int length) {
            return new double[length];
        }

        @Override
        public void normalizeColumn(double[] src, double[] dst, int n, int col) {
            int offset = col * n;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += src[offset + i];
            }
            double mean = sum / n;

            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double centered = src[offset + i] - mean;
                dst[offset + i] = centered;
                sumSq += centered * centered;
            }

            if (sumSq == 0) {
                // zero variance: the correlation coefficient is undefined
                for (int i = 0; i < n; i++) {
                    dst[offset + i] = Double.NaN;
                }
            } else {
                double invNorm = 1.0 / Math.sqrt(sumSq);
                for (int i = 0; i < n; i++) {
                    dst[offset + i] *= invNorm;
                }
            }
        }

        @Override
        public double dot(double[] data, int offsetI, int offsetJ, int n) {
            double r = 0;
            for (int row = 0; row < n; row++) {
                r += data[offsetI + row] * data[offsetJ + row];
            }
            return r;
        }

        @Override
        public void set(double[] data, int index, double value) {
            data[index] = value;
        }
    }

    private static final class FloatKernels implements Kernels<float[]> {

        @Override
        public float[] allocate(int length) {
            return new float[length];
        }

        @Override
        public void normalizeColumn(float[] src, float[] dst, int n, int col) {
            int offset = col * n;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += src[offset + i];
            }
            double mean = sum / n;

            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double centered = src[offset + i] - mean;
                dst[offset + i] = (float) centered;
                sumSq += centered * centered;
            }

            if (sumSq == 0) {
                // zero variance: the correlation coefficient is undefined
                for (int i = 0; i < n; i++) {
                    dst[offset + i] = Float.NaN;
                }
            } else {
                double invNorm = 1.0 / Math.sqrt(sumSq);
                for (int i = 0; i < n; i++) {
                    dst[offset + i] = (float) (dst[offset + i] * invNorm);
                }
            }
        }

        @Override
        public double dot(float[] data, int offsetI, int offsetJ, int n) {
            double r = 0;
            for (int row = 0; row < n; row++) {
                r += (double) data[offsetI + row] * data[offsetJ + row];
            }
            return r;
        }

        @Override
        public void set(float[] data, int index, double value) {
            data[index] = (float) value;
        }
    }
}
