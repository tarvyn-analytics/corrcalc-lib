package ch.corrcalc.lib.correlation;

import ch.corrcalc.lib.exception.InvalidInputException;
import ch.corrcalc.lib.matrix.Matrix;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearsonCorrelationCalculatorTest {

    private final PearsonCorrelationCalculator calculator = new PearsonCorrelationCalculator();

    @Test
    void calculate_TwoPerfectlyCorrelatedColumns_ReturnsCorrelationOne() {
        Matrix input = Matrix.fromRows(new double[][]{
                {1, 2},
                {2, 4},
                {3, 6}
        });
        double[][] result = calculator.calculate(input).toRowArrays();

        assertArrayEquals(new double[]{1.0, 1.0}, result[0], 1e-9);
        assertArrayEquals(new double[]{1.0, 1.0}, result[1], 1e-9);
    }

    @Test
    void calculate_TwoPerfectlyNegativeCorrelatedColumns_ReturnsCorrelationMinusOne() {
        Matrix input = Matrix.fromRows(new double[][]{
                {1, -1},
                {2, -2},
                {3, -3}
        });
        double[][] result = calculator.calculate(input).toRowArrays();

        assertEquals(-1.0, result[0][1], 1e-9);
        assertEquals(-1.0, result[1][0], 1e-9);
    }

    @Test
    void calculate_SingleColumn_ReturnsSingleOne() {
        Matrix input = Matrix.fromRows(new double[][]{{5}, {5}, {5}});
        Matrix result = calculator.calculate(input);

        assertEquals(1, result.rows());
        assertEquals(1, result.cols());
        assertEquals(1.0, result.get(0, 0), 1e-9);
    }

    @Test
    void calculate_ZeroVarianceColumns_ReturnsNaNCorrelation() {
        Matrix input = Matrix.fromRows(new double[][]{
                {2, 3},
                {2, 3},
                {2, 3}
        });
        double[][] result = calculator.calculate(input).toRowArrays();

        assertEquals(1.0, result[0][0], 1e-9);
        assertEquals(1.0, result[1][1], 1e-9);
        assertTrue(Double.isNaN(result[0][1]));
        assertTrue(Double.isNaN(result[1][0]));
    }

    @Test
    void calculate_IndependentColumns_ReturnsZeroCorrelation() {
        Matrix input = Matrix.fromRows(new double[][]{
                {1, 2},
                {2, 1},
                {3, 2}
        });
        double[][] result = calculator.calculate(input).toRowArrays();

        assertEquals(0.0, result[0][1], 1e-9);
        assertEquals(0.0, result[1][0], 1e-9);
    }

    @Test
    void calculate_ThreeColumnsMixedVariances_ReturnsCorrectCorrelations() {
        Matrix input = Matrix.fromRows(new double[][]{
                {1, 1, 3},
                {1, 2, 2},
                {1, 3, 1}
        });
        double[][] result = calculator.calculate(input).toRowArrays();

        // Check diagonal elements
        assertEquals(1.0, result[0][0], 1e-9);
        assertEquals(1.0, result[1][1], 1e-9);
        assertEquals(1.0, result[2][2], 1e-9);

        // Check correlations between zero-variance column and others
        assertTrue(Double.isNaN(result[0][1]));
        assertTrue(Double.isNaN(result[1][0]));
        assertTrue(Double.isNaN(result[0][2]));
        assertTrue(Double.isNaN(result[2][0]));

        // Check correlation between columns 1 and 2
        assertEquals(-1.0, result[1][2], 1e-9);
        assertEquals(-1.0, result[2][1], 1e-9);
    }

    @Test
    void calculate_TwoRowsTwoColumns_ComputesCorrectly() {
        Matrix input = Matrix.fromRows(new double[][]{
                {1, 2},
                {3, 4}
        });
        double[][] result = calculator.calculate(input).toRowArrays();

        assertEquals(1.0, result[0][1], 1e-9);
        assertEquals(1.0, result[1][0], 1e-9);
    }

    @Test
    void calculate_SingleRowInput_ReturnsNaNMatrix() {
        Matrix input = Matrix.fromRows(new double[][]{{5, 6}});
        double[][] result = calculator.calculate(input).toRowArrays();

        for (int i = 0; i < result.length; i++) {
            for (int j = 0; j < result[i].length; j++) {
                if (i == j) {
                    assertEquals(1, result[i][j]);
                } else {
                    assertTrue(Double.isNaN(result[i][j]));
                }
            }
        }
    }

    @Test
    void calculate_KnownTextbookExample_MatchesHandComputedValue() {
        // r for x={1,2,3,4,5} vs y={2,1,4,3,7} computed by hand:
        // cov = 12/4 = 3.0, var_x = 10/4 = 2.5, var_y = 21.2/4 = 5.3
        // r = 3 / sqrt(2.5 * 5.3) = 0.824163...
        Matrix input = Matrix.fromRows(new double[][]{
                {1, 2},
                {2, 1},
                {3, 4},
                {4, 3},
                {5, 7}
        });
        double[][] result = calculator.calculate(input).toRowArrays();

        double expected = 3.0 / Math.sqrt(2.5 * 5.3);
        assertEquals(expected, result[0][1], 1e-12);
        assertEquals(expected, result[1][0], 1e-12);
    }

    @Test
    void calculate_RandomSmallMatrix_MatchesNaiveReferenceImplementation() {
        Matrix input = randomMatrix(37, 7, 42L);

        assertMatchesReference(input, calculator.calculate(input));
    }

    @Test
    void calculate_LargeMatrixAboveParallelThreshold_MatchesNaiveReferenceImplementation() {
        int n = 250;
        int p = 50;
        assertTrue((long) n * p * p >= PearsonCorrelationCalculator.PARALLEL_THRESHOLD_FLOPS,
                "test matrix must be large enough to exercise the parallel path");
        Matrix input = randomMatrix(n, p, 4242L);

        assertMatchesReference(input, calculator.calculate(input));
    }

    @Test
    void calculate_RandomMatrix_ResultIsSymmetricWithUnitDiagonalAndBoundedValues() {
        Matrix result = calculator.calculate(randomMatrix(64, 9, 7L));

        assertEquals(9, result.rows());
        assertEquals(9, result.cols());
        for (int i = 0; i < result.rows(); i++) {
            assertEquals(1.0, result.get(i, i), 1e-12);
            for (int j = 0; j < i; j++) {
                assertEquals(result.get(i, j), result.get(j, i), 0.0);
                assertTrue(Math.abs(result.get(i, j)) <= 1.0 + 1e-12);
            }
        }
    }

    @Test
    void calculate_AffineTransformedColumns_ReturnsSameCorrelationMatrix() {
        Matrix original = randomMatrix(50, 5, 13L);
        Matrix transformed = original.copy();
        for (int j = 0; j < transformed.cols(); j++) {
            double scale = 0.5 + j;
            double shift = 100.0 * (j + 1);
            for (int i = 0; i < transformed.rows(); i++) {
                transformed.set(i, j, transformed.get(i, j) * scale + shift);
            }
        }

        double[] expected = calculator.calculate(original).data();
        double[] actual = calculator.calculate(transformed).data();

        assertArrayEquals(expected, actual, 1e-9);
    }

    @Test
    void calculate_EmptyInput_ThrowsInvalidInput() {
        Matrix noRows = Matrix.columnMajor(new double[0], 0, 0);
        assertThrows(InvalidInputException.class, () -> calculator.calculate(noRows));

        Matrix noCols = Matrix.columnMajor(new double[0], 3, 0);
        assertThrows(InvalidInputException.class, () -> calculator.calculate(noCols));
    }

    @Test
    void calculate_InputMatrix_IsNotModified() {
        Matrix input = randomMatrix(20, 4, 99L);
        Matrix snapshot = input.copy();

        calculator.calculate(input);

        assertEquals(snapshot, input);
    }

    private static Matrix randomMatrix(int rows, int cols, long seed) {
        Random random = new Random(seed);
        Matrix matrix = Matrix.zeros(rows, cols);
        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows; i++) {
                matrix.set(i, j, random.nextDouble() * 20 - 10);
            }
        }
        return matrix;
    }

    private static void assertMatchesReference(Matrix input, Matrix actual) {
        double[][] expected = referencePearson(input);
        for (int i = 0; i < expected.length; i++) {
            for (int j = 0; j < expected.length; j++) {
                assertEquals(expected[i][j], actual.get(i, j), 1e-10,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
    }

    /**
     * Naive textbook implementation used as an independent oracle: sample
     * covariance divided by the product of sample standard deviations.
     */
    private static double[][] referencePearson(Matrix input) {
        int n = input.rows();
        int p = input.cols();
        double[] means = new double[p];
        for (int j = 0; j < p; j++) {
            for (int i = 0; i < n; i++) {
                means[j] += input.get(i, j);
            }
            means[j] /= n;
        }
        double[] stdDev = new double[p];
        for (int j = 0; j < p; j++) {
            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double c = input.get(i, j) - means[j];
                sumSq += c * c;
            }
            stdDev[j] = Math.sqrt(sumSq / (n - 1));
        }
        double[][] corr = new double[p][p];
        for (int a = 0; a < p; a++) {
            corr[a][a] = 1.0;
            for (int b = 0; b < a; b++) {
                double cov = 0;
                for (int i = 0; i < n; i++) {
                    cov += (input.get(i, a) - means[a]) * (input.get(i, b) - means[b]);
                }
                cov /= (n - 1);
                double r = cov / (stdDev[a] * stdDev[b]);
                corr[a][b] = r;
                corr[b][a] = r;
            }
        }
        return corr;
    }
}
