package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Calculates the Kendall rank correlation matrix (tau-b), which measures ordinal
 * association through the balance of concordant and discordant observation pairs,
 * with the {@code -b} tie correction.
 * <p>
 * Algorithm — Knight's {@code O(n log n)} method per column pair, rather than the
 * naive {@code O(n^2)} pair enumeration. For columns {@code x} and {@code y}:
 * sort the rows by {@code (x, then y)}; the number of <i>discordant</i> pairs is
 * then the number of inversions in {@code y} under that order, counted while
 * merge-sorting it. With {@code n0 = n(n-1)/2} total pairs, {@code n1}/{@code n2}
 * the pairs tied in {@code x}/{@code y}, and {@code n3} the pairs tied in both,
 * {@code C - D = n0 - n1 - n2 + n3 - 2*D} and
 * {@code tau_b = (C - D) / sqrt((n0 - n1)(n0 - n2))}.
 * <p>
 * All the combinatorics are exact integer counts accumulated in {@code long}
 * (the products in the denominator are formed in {@code double} to avoid
 * overflow), and tau depends only on the ordering of values, so single-precision
 * input yields exactly the same counts — the float paths simply round the final
 * coefficient. A constant column ties every pair, so its denominator is zero and
 * it yields {@code NaN} against every other column, the diagonal staying
 * {@code 1}, as with the other correlation types.
 * <p>
 * The work parallelizes across the columns of the upper triangle: each column
 * {@code j} fills the pairs {@code (i, j)} for {@code i < j} (and their mirror),
 * disjoint index sets safe to run concurrently. Sorting does not benefit from
 * the SIMD kernels, so every {@link Profile} runs this same scalar algorithm.
 */
final class KendallCorrelationCalculator implements CorrelationCalculator {

    /**
     * Work proxy {@code n*p*p} below which forking the per-pair sorts to the
     * common pool costs more than it saves. Lower than Pearson's threshold
     * because each pair does {@code O(n log n)} sorting work, not {@code O(n)}.
     */
    static final long PARALLEL_THRESHOLD = 1L << 16;

    @FunctionalInterface
    private interface ColumnComparator {
        /** Compares rows {@code a} and {@code b} by the value in column {@code col}. */
        int compare(int col, int a, int b);
    }

    @FunctionalInterface
    private interface ResultSetter {
        void set(int index, double value);
    }

    @Override
    public DoubleMatrix calculate(DoubleMatrix observations) {
        int n = observations.rows();
        int p = observations.cols();
        double[] data = observations.data();
        double[] corr = new double[resultLength(p)];
        correlate(n, p, tieSums(data, n, p),
                (col, a, b) -> Double.compare(data[col * n + a], data[col * n + b]),
                (index, value) -> corr[index] = value);
        return DoubleMatrix.columnMajor(corr, p, p);
    }

    @Override
    public FloatMatrix calculate(FloatMatrix observations) {
        int n = observations.rows();
        int p = observations.cols();
        float[] data = observations.data();
        float[] corr = new float[resultLength(p)];
        correlate(n, p, tieSums(data, n, p),
                (col, a, b) -> Float.compare(data[col * n + a], data[col * n + b]),
                (index, value) -> corr[index] = (float) value);
        return FloatMatrix.columnMajor(corr, p, p);
    }

    @Override
    public DoubleMatrix calculateToDouble(FloatMatrix observations) {
        int n = observations.rows();
        int p = observations.cols();
        float[] data = observations.data();
        double[] corr = new double[resultLength(p)];
        correlate(n, p, tieSums(data, n, p),
                (col, a, b) -> Float.compare(data[col * n + a], data[col * n + b]),
                (index, value) -> corr[index] = value);
        return DoubleMatrix.columnMajor(corr, p, p);
    }

    private static int resultLength(int p) {
        return p < 1 ? 0 : Math.multiplyExact(p, p);
    }

    /**
     * Fills the symmetric tau-b matrix. {@code tieSum[c]} is the pre-computed
     * {@code sum t(t-1)/2} over the tied-value groups of column {@code c}, which
     * supplies {@code n1} and {@code n2} for every pair without re-sorting.
     */
    private static void correlate(int n, int p, long[] tieSum, ColumnComparator cmp, ResultSetter corr) {
        if (n < 1 || p < 1) {
            throw new InvalidInputException(
                    "At least one observation row and one variable column are required, got [" + n + "x" + p + "]");
        }
        long n0 = (long) n * (n - 1) / 2;
        boolean parallel = (long) n * p * p >= PARALLEL_THRESHOLD;
        columns(p, parallel).forEach(j -> {
            corr.set(j * p + j, 1.0);
            int[] order = new int[n];
            int[] scratch = new int[n];
            for (int i = 0; i < j; i++) {
                double tau = tauB(n, n0, tieSum[i], tieSum[j], cmp, i, j, order, scratch);
                corr.set(j * p + i, tau);
                corr.set(i * p + j, tau);
            }
        });
    }

    private static double tauB(int n, long n0, long tieX, long tieY, ColumnComparator cmp,
                               int colX, int colY, int[] order, int[] scratch) {
        for (int k = 0; k < n; k++) {
            order[k] = k;
        }
        mergeSortLex(order, scratch, 0, n, cmp, colX, colY);
        long jointTies = jointTieSum(order, n, cmp, colX, colY);
        long discordant = mergeCountInversions(order, scratch, 0, n, cmp, colY);
        long concordantMinusDiscordant = n0 - tieX - tieY + jointTies - 2 * discordant;
        double denominator = Math.sqrt((double) (n0 - tieX) * (double) (n0 - tieY));
        return denominator == 0 ? Double.NaN : concordantMinusDiscordant / denominator;
    }

    /** {@code sum t(t-1)/2} over the runs tied in both {@code x} and {@code y}. */
    private static long jointTieSum(int[] order, int n, ColumnComparator cmp, int colX, int colY) {
        long sum = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n
                    && cmp.compare(colX, order[j + 1], order[i]) == 0
                    && cmp.compare(colY, order[j + 1], order[i]) == 0) {
                j++;
            }
            long t = j - i + 1;
            sum += t * (t - 1) / 2;
            i = j + 1;
        }
        return sum;
    }

    /** Stable merge sort of {@code order} by {@code (colX, then colY)}. */
    private static void mergeSortLex(int[] a, int[] tmp, int lo, int hi, ColumnComparator cmp, int colX, int colY) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSortLex(a, tmp, lo, mid, cmp, colX, colY);
        mergeSortLex(a, tmp, mid, hi, cmp, colX, colY);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (lexCompare(cmp, colX, colY, a[i], a[j]) <= 0) {
                tmp[k++] = a[i++];
            } else {
                tmp[k++] = a[j++];
            }
        }
        while (i < mid) {
            tmp[k++] = a[i++];
        }
        while (j < hi) {
            tmp[k++] = a[j++];
        }
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }

    private static int lexCompare(ColumnComparator cmp, int colX, int colY, int a, int b) {
        int byX = cmp.compare(colX, a, b);
        return byX != 0 ? byX : cmp.compare(colY, a, b);
    }

    /**
     * Sorts {@code order} by column {@code col} and returns the number of
     * inversions removed — the count of pairs out of order in {@code col}, i.e.
     * the discordant pairs once {@code order} is the {@code (x, y)} lexsort.
     */
    private static long mergeCountInversions(int[] a, int[] tmp, int lo, int hi, ColumnComparator cmp, int col) {
        if (hi - lo < 2) {
            return 0;
        }
        int mid = (lo + hi) >>> 1;
        long count = mergeCountInversions(a, tmp, lo, mid, cmp, col)
                + mergeCountInversions(a, tmp, mid, hi, cmp, col);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (cmp.compare(col, a[i], a[j]) <= 0) {
                tmp[k++] = a[i++];
            } else {
                tmp[k++] = a[j++];
                count += mid - i; // every remaining left element outranks a[j]
            }
        }
        while (i < mid) {
            tmp[k++] = a[i++];
        }
        while (j < hi) {
            tmp[k++] = a[j++];
        }
        System.arraycopy(tmp, lo, a, lo, hi - lo);
        return count;
    }

    /** {@code sum t(t-1)/2} over the tied-value groups of each column. */
    private static long[] tieSums(double[] data, int n, int p) {
        long[] sums = new long[Math.max(p, 0)];
        double[] column = new double[Math.max(n, 0)];
        for (int c = 0; c < p; c++) {
            System.arraycopy(data, c * n, column, 0, n);
            Arrays.sort(column);
            long sum = 0;
            int i = 0;
            while (i < n) {
                int j = i;
                while (j + 1 < n && Double.compare(column[j + 1], column[i]) == 0) {
                    j++;
                }
                long t = j - i + 1;
                sum += t * (t - 1) / 2;
                i = j + 1;
            }
            sums[c] = sum;
        }
        return sums;
    }

    /** {@code sum t(t-1)/2} over the tied-value groups of each column. */
    private static long[] tieSums(float[] data, int n, int p) {
        long[] sums = new long[Math.max(p, 0)];
        float[] column = new float[Math.max(n, 0)];
        for (int c = 0; c < p; c++) {
            System.arraycopy(data, c * n, column, 0, n);
            Arrays.sort(column);
            long sum = 0;
            int i = 0;
            while (i < n) {
                int j = i;
                while (j + 1 < n && Float.compare(column[j + 1], column[i]) == 0) {
                    j++;
                }
                long t = j - i + 1;
                sum += t * (t - 1) / 2;
                i = j + 1;
            }
            sums[c] = sum;
        }
        return sums;
    }

    private static IntStream columns(int p, boolean parallel) {
        IntStream range = IntStream.range(0, p);
        return parallel ? range.parallel() : range.sequential();
    }
}
