package ch.tarvynanalytics.corrcalc.lib.correlation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymmetricPositiveDefiniteInverseTest {

    @Test
    void invert_SingleElement_ReturnsReciprocal() {
        double[] inverse = SymmetricPositiveDefiniteInverse.invert(new double[]{4.0}, 1);

        assertNotNull(inverse);
        assertEquals(0.25, inverse[0], 1e-15);
    }

    @Test
    void invert_Identity_ReturnsIdentity() {
        int p = 5;
        double[] identity = identity(p);

        double[] inverse = SymmetricPositiveDefiniteInverse.invert(identity, p);

        assertArrayEquals(identity, inverse, 1e-15);
    }

    @Test
    void invert_KnownTwoByTwo_MatchesHandComputedInverse() {
        // A = [[4, 2], [2, 3]], det = 8, A^-1 = 1/8 * [[3, -2], [-2, 4]]
        double[] a = {4, 2, 2, 3};

        double[] inverse = SymmetricPositiveDefiniteInverse.invert(a, 2);

        assertNotNull(inverse);
        assertArrayEquals(new double[]{0.375, -0.25, -0.25, 0.5}, inverse, 1e-15);
    }

    @Test
    void invert_RandomSpdMatrices_ProductWithInverseIsIdentity() {
        for (int p = 1; p <= 12; p++) {
            double[] a = randomSpd(p, 1_000 + p);

            double[] inverse = SymmetricPositiveDefiniteInverse.invert(a, p);

            assertNotNull(inverse, "matrix of size " + p + " must be invertible");
            assertArrayEquals(identity(p), multiply(a, inverse, p), 1e-9,
                    "A * A^-1 must be the identity for size " + p);
        }
    }

    @Test
    void invert_RandomSpdMatrices_MatchesGaussJordanOracle() {
        for (int p = 2; p <= 12; p++) {
            double[] a = randomSpd(p, 7_000 + p);

            double[] actual = SymmetricPositiveDefiniteInverse.invert(a, p);

            assertNotNull(actual);
            assertArrayEquals(gaussJordanInverse(a, p), actual, 1e-9,
                    "Cholesky inverse must match the Gauss-Jordan oracle for size " + p);
        }
    }

    @Test
    void invert_SingularMatrix_ReturnsNull() {
        // two identical columns: rank 1, not invertible
        double[] singular = {1, 1, 1, 1};

        assertNull(SymmetricPositiveDefiniteInverse.invert(singular, 2));
    }

    @Test
    void invert_RankDeficientThreeByThree_ReturnsNull() {
        // row/col 0 equals row/col 2, so the matrix is singular
        double[] singular = {
                2, 0, 2,
                0, 1, 0,
                2, 0, 2
        };

        assertNull(SymmetricPositiveDefiniteInverse.invert(singular, 3));
    }

    @Test
    void invert_IndefiniteMatrix_ReturnsNull() {
        // [[1, 2], [2, 1]] has eigenvalues 3 and -1, so it is not positive definite
        double[] indefinite = {1, 2, 2, 1};

        assertNull(SymmetricPositiveDefiniteInverse.invert(indefinite, 2));
    }

    @Test
    void invert_NaNEntries_PropagateNaNRatherThanReportSingular() {
        // a zero-variance column shows up as NaN off-diagonals with a unit
        // diagonal: the inverse must come back NaN, not null
        double[] withNaN = {1, Double.NaN, Double.NaN, 1};

        double[] inverse = SymmetricPositiveDefiniteInverse.invert(withNaN, 2);

        assertNotNull(inverse);
        assertTrue(Double.isNaN(inverse[1]) || Double.isNaN(inverse[0]),
                "NaN input must propagate into the inverse");
    }

    @Test
    void invert_IllConditionedMatrix_StaysAccurate() {
        // near-collinear but still positive definite (correlation 0.999999)
        double[] a = {1.0, 0.999999, 0.999999, 1.0};

        double[] inverse = SymmetricPositiveDefiniteInverse.invert(a, 2);

        assertNotNull(inverse);
        assertArrayEquals(gaussJordanInverse(a, 2), inverse, 1e-3);
        assertArrayEquals(identity(2), multiply(a, inverse, 2), 1e-6);
    }

    @Test
    void invert_InputMatrix_IsNotModified() {
        double[] a = randomSpd(6, 999);
        double[] snapshot = a.clone();

        SymmetricPositiveDefiniteInverse.invert(a, 6);

        assertArrayEquals(snapshot, a, 0.0);
    }

    private static double[] identity(int p) {
        double[] identity = new double[p * p];
        for (int i = 0; i < p; i++) {
            identity[i * p + i] = 1.0;
        }
        return identity;
    }

    /** A well-conditioned SPD matrix {@code M*M^T + p*I}, column-major. */
    private static double[] randomSpd(int p, long seed) {
        Random random = new Random(seed);
        double[] m = new double[p * p];
        for (int i = 0; i < m.length; i++) {
            m[i] = random.nextDouble() * 2 - 1;
        }
        double[] a = new double[p * p];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                double s = 0;
                for (int k = 0; k < p; k++) {
                    s += m[i * p + k] * m[j * p + k];
                }
                a[j * p + i] = s + (i == j ? p : 0);
            }
        }
        return a;
    }

    /** Column-major matrix product {@code A * B}. */
    private static double[] multiply(double[] a, double[] b, int p) {
        double[] c = new double[p * p];
        for (int i = 0; i < p; i++) {
            for (int k = 0; k < p; k++) {
                double s = 0;
                for (int j = 0; j < p; j++) {
                    s += a[j * p + i] * b[k * p + j];
                }
                c[k * p + i] = s;
            }
        }
        return c;
    }

    /**
     * Independent oracle: a general Gauss-Jordan inverse with partial pivoting,
     * deliberately a different algorithm from the Cholesky route under test.
     */
    private static double[] gaussJordanInverse(double[] matrix, int p) {
        double[][] aug = new double[p][2 * p];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                aug[i][j] = matrix[j * p + i];
            }
            aug[i][p + i] = 1.0;
        }
        for (int col = 0; col < p; col++) {
            int pivot = col;
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(aug[r][col]) > Math.abs(aug[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = aug[col];
            aug[col] = aug[pivot];
            aug[pivot] = tmp;
            double diag = aug[col][col];
            for (int j = 0; j < 2 * p; j++) {
                aug[col][j] /= diag;
            }
            for (int r = 0; r < p; r++) {
                if (r == col) {
                    continue;
                }
                double factor = aug[r][col];
                for (int j = 0; j < 2 * p; j++) {
                    aug[r][j] -= factor * aug[col][j];
                }
            }
        }
        double[] inverse = new double[p * p];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                inverse[j * p + i] = aug[i][p + j];
            }
        }
        return inverse;
    }
}
