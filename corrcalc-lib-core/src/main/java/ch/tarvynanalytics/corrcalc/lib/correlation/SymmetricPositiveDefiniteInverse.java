package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * Inverts a small symmetric positive-definite matrix via Cholesky
 * decomposition. Used by {@link PartialCorrelationCalculator} to turn the
 * Pearson correlation matrix into its precision matrix; the {@code p x p}
 * inputs are tiny next to the {@code n x p} observations, so this stays a
 * straightforward double-only scalar routine with no profile machinery.
 * <p>
 * Cholesky is chosen over a general LU/Gauss-Jordan inverse because the input
 * is symmetric positive-definite: the factorization is about twice as cheap and
 * a non-positive pivot is exactly the signal that the matrix is singular or
 * indefinite — which the caller turns into its degenerate-input policy.
 */
final class SymmetricPositiveDefiniteInverse {

    /**
     * A Cholesky pivot at or below this fraction of the column's original
     * diagonal entry marks the matrix as not positive definite. Correlation
     * matrices have a unit diagonal, so this is effectively an absolute
     * {@code 1e-12} threshold; it separates genuine singularity (collinear
     * columns drive the pivot to zero) from merely ill-conditioned inputs,
     * which keep a small but safely positive pivot.
     */
    private static final double PIVOT_TOLERANCE = 1e-12;

    private SymmetricPositiveDefiniteInverse() {
        // no instance
    }

    /**
     * Inverts a {@code p x p} symmetric positive-definite matrix supplied as a
     * flat column-major array (element {@code (row, col)} at {@code col*p+row}).
     * Only the values are read; the input is never modified, and only the lower
     * triangle is consulted (symmetry is assumed).
     *
     * @return the inverse in the same column-major layout, or {@code null} if
     *         the matrix is not positive definite — a non-positive Cholesky
     *         pivot, the signal that it is singular or indefinite. {@code NaN}
     *         entries (e.g. from a zero-variance column) are not flagged as
     *         non-PD: they propagate into the result, so the caller can tell an
     *         undefined input apart from a genuinely singular one.
     */
    // null is the documented "not positive definite" sentinel, distinct from a
    // valid (possibly NaN-bearing) inverse; the sole caller handles it explicitly
    @SuppressWarnings("java:S1168")
    static double[] invert(double[] matrix, int p) {
        double[] l = choleskyLower(matrix, p);
        if (l == null) {
            return null;
        }
        double[] lInv = invertLowerTriangular(l, p);
        return multiplyTransposeWithSelf(lInv, p);
    }

    /**
     * Computes the lower-triangular Cholesky factor {@code L} with
     * {@code matrix = L * L^T}, stored column-major. Returns {@code null} on the
     * first non-positive pivot.
     */
    @SuppressWarnings("java:S1168") // null signals a non-positive pivot to invert()
    private static double[] choleskyLower(double[] matrix, int p) {
        double[] l = new double[p * p];
        for (int j = 0; j < p; j++) {
            double pivot = matrix[j * p + j];
            for (int k = 0; k < j; k++) {
                double ljk = l[k * p + j];
                pivot -= ljk * ljk;
            }
            if (pivot <= PIVOT_TOLERANCE * matrix[j * p + j]) {
                return null; // not positive definite: singular or indefinite
            }
            double ljj = Math.sqrt(pivot);
            l[j * p + j] = ljj;
            for (int i = j + 1; i < p; i++) {
                double s = matrix[j * p + i];
                for (int k = 0; k < j; k++) {
                    s -= l[k * p + i] * l[k * p + j];
                }
                l[j * p + i] = s / ljj;
            }
        }
        return l;
    }

    /** Inverts the lower-triangular factor {@code L} in place into a fresh array. */
    private static double[] invertLowerTriangular(double[] l, int p) {
        double[] inv = new double[p * p];
        for (int j = 0; j < p; j++) {
            inv[j * p + j] = 1.0 / l[j * p + j];
            for (int i = j + 1; i < p; i++) {
                double s = 0;
                for (int k = j; k < i; k++) {
                    s += l[k * p + i] * inv[j * p + k];
                }
                inv[j * p + i] = -s / l[i * p + i];
            }
        }
        return inv;
    }

    /**
     * Forms the symmetric product {@code M^T * M} for a lower-triangular
     * {@code M}, which equals {@code L^-T * L^-1 = (L*L^T)^-1} — the inverse of
     * the original matrix.
     */
    private static double[] multiplyTransposeWithSelf(double[] m, int p) {
        double[] inv = new double[p * p];
        for (int j = 0; j < p; j++) {
            for (int i = 0; i <= j; i++) {
                double s = 0;
                for (int k = j; k < p; k++) { // M lower-triangular: rows k >= max(i, j) == j
                    s += m[i * p + k] * m[j * p + k];
                }
                inv[j * p + i] = s;
                inv[i * p + j] = s;
            }
        }
        return inv;
    }
}
