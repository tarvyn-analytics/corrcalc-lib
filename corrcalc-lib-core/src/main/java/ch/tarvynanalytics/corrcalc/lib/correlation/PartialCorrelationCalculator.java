package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;

/**
 * Calculates the partial correlation matrix: each coefficient is the
 * correlation between two variables with the linear effect of all the other
 * variables removed.
 * <p>
 * Algorithm — the standard precision-matrix route. Compute the Pearson
 * correlation matrix {@code R}, invert it to the precision matrix
 * {@code P = R^-1}, then read off
 * {@code rho_ij = -P_ij / sqrt(P_ii * P_jj)} with ones on the diagonal. This
 * reuses the full Pearson machinery (every {@link Profile}, every kernel) for
 * the only {@code O(n*p^2)} part of the work; the added {@code O(p^3)} inversion
 * is negligible whenever {@code n >> p}.
 * <p>
 * <b>Precision.</b> The inversion amplifies error by the matrix condition
 * number, and a {@code p x p} matrix is tiny, so {@code R} is always computed
 * and inverted in double — even for {@link FloatMatrix} input, which is routed
 * through {@link CorrelationCalculator#calculateToDouble}. Partial correlation
 * therefore has no chunked-float fast path: the single-precision API just rounds
 * the double result, honouring the {@link CorrelationCalculator} precision
 * contract.
 * <p>
 * <b>Degenerate inputs.</b> A zero-variance column makes its row and column of
 * {@code R} {@code NaN}; the inversion mixes every entry, so that {@code NaN}
 * spreads to the whole result off the diagonal (the diagonal stays {@code 1}) —
 * a constant variable leaves the partials undefined, consistent with Pearson
 * yielding {@code NaN} against such a column. A genuinely singular {@code R}
 * (collinear columns, or fewer observations than variables) has no precision
 * matrix at all, so it is rejected with an {@link InvalidInputException}.
 */
final class PartialCorrelationCalculator implements CorrelationCalculator {

    private final CorrelationCalculator pearson;

    /**
     * @param pearson the Pearson calculator for the chosen {@link Profile}; its
     *                profile drives the {@code R} computation, while the
     *                inversion is always double and profile-agnostic
     */
    PartialCorrelationCalculator(CorrelationCalculator pearson) {
        this.pearson = pearson;
    }

    @Override
    public DoubleMatrix calculate(DoubleMatrix observations) {
        return partialFrom(pearson.calculate(observations), observations.rows());
    }

    @Override
    public FloatMatrix calculate(FloatMatrix observations) {
        DoubleMatrix partial = partialFrom(pearson.calculateToDouble(observations), observations.rows());
        double[] values = partial.data();
        float[] rounded = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            rounded[i] = (float) values[i];
        }
        return FloatMatrix.columnMajor(rounded, partial.rows(), partial.cols());
    }

    @Override
    public DoubleMatrix calculateToDouble(FloatMatrix observations) {
        return partialFrom(pearson.calculateToDouble(observations), observations.rows());
    }

    /**
     * Turns a Pearson correlation matrix into the partial correlation matrix.
     * {@code n} is carried only for the error message on a singular input.
     */
    private static DoubleMatrix partialFrom(DoubleMatrix correlation, int n) {
        int p = correlation.cols();
        double[] precision = SymmetricPositiveDefiniteInverse.invert(correlation.data(), p);
        if (precision == null) {
            throw new InvalidInputException(
                    "Partial correlation requires a non-singular correlation matrix"
                            + " (collinear columns or fewer observations than variables), got ["
                            + n + "x" + p + "]");
        }
        double[] partial = new double[p * p];
        for (int j = 0; j < p; j++) {
            partial[j * p + j] = 1.0;
            double pjj = precision[j * p + j];
            for (int i = 0; i < j; i++) {
                double r = -precision[j * p + i] / Math.sqrt(precision[i * p + i] * pjj);
                partial[j * p + i] = r;
                partial[i * p + j] = r;
            }
        }
        return DoubleMatrix.columnMajor(partial, p, p);
    }
}
