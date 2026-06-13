package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;

/**
 * Calculates the Spearman rank correlation matrix: the Pearson correlation of
 * the columns' ranks. It measures monotonic rather than linear association, so
 * any strictly monotonic relationship — however non-linear — comes out as
 * {@code +/-1}.
 * <p>
 * Algorithm: replace each column with its average ranks (see
 * {@link RankTransform}), then delegate to the Pearson calculator of the chosen
 * {@link Profile}. Ranking is an {@code O(n log n)} per-column transform; the
 * correlation itself is then plain Pearson, inheriting every profile and the
 * full precision contract unchanged. Ranks are produced in the same element type
 * as the input — exact in double, and exact in float for any realistic row count
 * — so the single-precision paths keep their halved working set.
 * <p>
 * A constant column has all-equal ranks (zero variance), so it yields
 * {@code NaN} against every other column with the diagonal staying {@code 1},
 * exactly as Pearson treats a zero-variance column.
 */
final class SpearmanCorrelationCalculator implements CorrelationCalculator {

    private final CorrelationCalculator pearson;

    /**
     * @param pearson the Pearson calculator for the chosen {@link Profile}; it
     *                runs on the ranked columns, so the profile drives the whole
     *                computation
     */
    SpearmanCorrelationCalculator(CorrelationCalculator pearson) {
        this.pearson = pearson;
    }

    @Override
    public DoubleMatrix calculate(DoubleMatrix observations) {
        return pearson.calculate(ranked(observations));
    }

    @Override
    public FloatMatrix calculate(FloatMatrix observations) {
        return pearson.calculate(ranked(observations));
    }

    @Override
    public DoubleMatrix calculateToDouble(FloatMatrix observations) {
        return pearson.calculateToDouble(ranked(observations));
    }

    private static DoubleMatrix ranked(DoubleMatrix observations) {
        int n = observations.rows();
        int p = observations.cols();
        double[] source = observations.data();
        double[] ranks = new double[source.length];
        for (int j = 0; j < p; j++) {
            RankTransform.rankColumn(source, ranks, j * n, n);
        }
        return DoubleMatrix.columnMajor(ranks, n, p);
    }

    private static FloatMatrix ranked(FloatMatrix observations) {
        int n = observations.rows();
        int p = observations.cols();
        float[] source = observations.data();
        float[] ranks = new float[source.length];
        for (int j = 0; j < p; j++) {
            RankTransform.rankColumn(source, ranks, j * n, n);
        }
        return FloatMatrix.columnMajor(ranks, n, p);
    }
}
