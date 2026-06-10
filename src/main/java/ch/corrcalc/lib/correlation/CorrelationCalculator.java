package ch.corrcalc.lib.correlation;

import ch.corrcalc.lib.matrix.DoubleMatrix;
import ch.corrcalc.lib.matrix.FloatMatrix;

/**
 * Calculates a correlation matrix from an observations matrix.
 * <p>
 * The input is an {@code n x p} matrix with one observation per row and one
 * variable per column; the result is the symmetric {@code p x p} matrix of
 * pairwise correlation coefficients with ones on the diagonal.
 */
public interface CorrelationCalculator {

    /**
     * @param observations an {@code n x p} matrix, {@code n >= 1}, free of NaN/Infinity
     *                     (use the {@code prep} package to clean raw data first)
     * @return the {@code p x p} correlation matrix
     */
    DoubleMatrix calculate(DoubleMatrix observations);

    /**
     * Single-precision variant of {@link #calculate(DoubleMatrix)} for memory-constrained
     * datasets. Storage and data transfer are halved while all sums still accumulate
     * in double precision, so the result is accurate to roughly single-precision
     * resolution (~1e-7) rather than degraded further by the summation.
     *
     * @param observations an {@code n x p} matrix, {@code n >= 1}, free of NaN/Infinity
     * @return the {@code p x p} correlation matrix
     */
    FloatMatrix calculate(FloatMatrix observations);
}
