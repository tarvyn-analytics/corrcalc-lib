package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;

/**
 * Calculates a correlation matrix from an observations matrix.
 * <p>
 * The input is an {@code n x p} matrix with one observation per row and one
 * variable per column; the result is the symmetric {@code p x p} matrix of
 * pairwise correlation coefficients with ones on the diagonal.
 * <p>
 * <b>Precision contract:</b> sums always accumulate at least as precisely as
 * the result type. Methods returning a {@link DoubleMatrix} accumulate in
 * double, unconditionally. The float→float path may accumulate single
 * precision in bounded chunks when the profile supports it, because the
 * resulting error stays below the float result's own rounding step — pick
 * {@link #calculateToDouble(FloatMatrix)} when you want the most accurate
 * coefficients single-precision input can support.
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
     * datasets: storage and data transfer are halved, and the result is accurate to
     * roughly single-precision resolution (~1e-7) — the rounding of the float result
     * format dominates any accumulation effects.
     *
     * @param observations an {@code n x p} matrix, {@code n >= 1}, free of NaN/Infinity
     * @return the {@code p x p} correlation matrix
     */
    FloatMatrix calculate(FloatMatrix observations);

    /**
     * Like {@link #calculate(FloatMatrix)} but returns the coefficients in double
     * precision, unrounded: all sums accumulate in double on every profile, giving
     * the most accurate result single-precision input can support — typically an
     * order of magnitude better than the ~1e-7 float result rounding, limited by
     * the float working buffer — while the big {@code n x p} input stays half-size.
     *
     * @param observations an {@code n x p} matrix, {@code n >= 1}, free of NaN/Infinity
     * @return the {@code p x p} correlation matrix in double precision
     */
    DoubleMatrix calculateToDouble(FloatMatrix observations);
}
