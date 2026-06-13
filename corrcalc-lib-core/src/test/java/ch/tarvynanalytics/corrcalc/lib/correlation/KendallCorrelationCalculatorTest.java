package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.matrix.FloatMatrix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KendallCorrelationCalculatorTest {

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_PerfectlyConcordantColumns_ReturnsOne(Profile profile) {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {2, 4},
                {3, 6},
                {4, 8}
        });

        assertEquals(1.0, calculator(profile).calculate(input).get(0, 1), 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_PerfectlyDiscordantColumns_ReturnsMinusOne(Profile profile) {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 8},
                {2, 6},
                {3, 4},
                {4, 2}
        });

        assertEquals(-1.0, calculator(profile).calculate(input).get(0, 1), 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_KnownSmallExample_MatchesHandComputedValue(Profile profile) {
        // x = {1,2,3,4}, y = {1,3,2,4}: 5 concordant, 1 discordant, no ties
        // tau = (5 - 1) / 6 = 0.6667
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 1},
                {2, 3},
                {3, 2},
                {4, 4}
        });

        assertEquals(4.0 / 6.0, calculator(profile).calculate(input).get(0, 1), 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_RandomSmallMatrix_MatchesNaiveOracle(Profile profile) {
        DoubleMatrix input = randomMatrix(45, 6, 42L);

        assertMatchesReference(input, calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_DataWithTies_MatchesNaiveOracle(Profile profile) {
        // small integer range forces many ties in both x and y, exercising the
        // tau-b tie corrections
        Random random = new Random(99L);
        DoubleMatrix input = DoubleMatrix.zeros(70, 5);
        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 70; i++) {
                input.set(i, j, random.nextInt(6));
            }
        }

        assertMatchesReference(input, calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_LargeMatrixAboveParallelThreshold_MatchesNaiveOracle(Profile profile) {
        int n = 200;
        int p = 20;
        assertTrue((long) n * p * p >= KendallCorrelationCalculator.PARALLEL_THRESHOLD,
                "test matrix must be large enough to exercise the parallel path");
        DoubleMatrix input = randomMatrix(n, p, 4242L);

        assertMatchesReference(input, calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_RandomMatrix_ResultIsSymmetricWithUnitDiagonalAndBoundedValues(Profile profile) {
        DoubleMatrix result = calculator(profile).calculate(randomMatrix(64, 9, 7L));

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

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_ConstantColumn_ReturnsNaNCorrelation(Profile profile) {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {5, 1},
                {5, 2},
                {5, 3}
        });

        double[][] result = calculator(profile).calculate(input).toRowArrays();

        assertEquals(1.0, result[0][0], 1e-12);
        assertEquals(1.0, result[1][1], 1e-12);
        assertTrue(Double.isNaN(result[0][1]));
        assertTrue(Double.isNaN(result[1][0]));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_SingleColumn_ReturnsSingleOne(Profile profile) {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{{3}, {1}, {2}});
        DoubleMatrix result = calculator(profile).calculate(input);

        assertEquals(1, result.rows());
        assertEquals(1, result.cols());
        assertEquals(1.0, result.get(0, 0), 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_EmptyInput_ThrowsInvalidInput(Profile profile) {
        DoubleMatrix noRows = DoubleMatrix.columnMajor(new double[0], 0, 0);
        assertThrows(InvalidInputException.class, () -> calculator(profile).calculate(noRows));

        DoubleMatrix noCols = DoubleMatrix.columnMajor(new double[0], 3, 0);
        assertThrows(InvalidInputException.class, () -> calculator(profile).calculate(noCols));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_InputMatrix_IsNotModified(Profile profile) {
        DoubleMatrix input = randomMatrix(30, 5, 99L);
        DoubleMatrix snapshot = input.copy();

        calculator(profile).calculate(input);

        assertEquals(snapshot, input);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_FloatRandomMatrix_MatchesDoubleResult(Profile profile) {
        DoubleMatrix input = randomMatrix(60, 6, 24L);
        FloatMatrix floatInput = floatCopyOf(input);

        DoubleMatrix expected = calculator(profile).calculate(doubleCopyOf(floatInput));
        FloatMatrix actual = calculator(profile).calculate(floatInput);

        for (int i = 0; i < expected.rows(); i++) {
            for (int j = 0; j < expected.cols(); j++) {
                assertEquals(expected.get(i, j), actual.get(i, j), 1e-6,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculateToDouble_FloatRandomMatrix_MatchesNaiveOracle(Profile profile) {
        // tau is purely ordinal, so float input produces exactly the same counts
        // as the quantized double values the oracle sees
        DoubleMatrix input = randomMatrix(80, 6, 4242L);
        FloatMatrix floatInput = floatCopyOf(input);

        DoubleMatrix actual = calculator(profile).calculateToDouble(floatInput);

        assertMatchesReference(doubleCopyOf(floatInput), actual);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_FloatInputMatrix_IsNotModified(Profile profile) {
        FloatMatrix input = floatCopyOf(randomMatrix(20, 4, 99L));
        FloatMatrix snapshot = input.copy();

        calculator(profile).calculate(input);

        assertEquals(snapshot, input);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculateToDouble_EmptyInput_ThrowsInvalidInput(Profile profile) {
        FloatMatrix noRows = FloatMatrix.columnMajor(new float[0], 0, 0);
        assertThrows(InvalidInputException.class, () -> calculator(profile).calculateToDouble(noRows));
    }

    private static KendallCorrelationCalculator calculator(Profile profile) {
        return (KendallCorrelationCalculator) Correlations.kendall(profile);
    }

    private static DoubleMatrix randomMatrix(int rows, int cols, long seed) {
        Random random = new Random(seed);
        DoubleMatrix matrix = DoubleMatrix.zeros(rows, cols);
        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows; i++) {
                matrix.set(i, j, random.nextDouble() * 20 - 10);
            }
        }
        return matrix;
    }

    private static FloatMatrix floatCopyOf(DoubleMatrix input) {
        FloatMatrix result = FloatMatrix.zeros(input.rows(), input.cols());
        for (int j = 0; j < input.cols(); j++) {
            for (int i = 0; i < input.rows(); i++) {
                result.set(i, j, (float) input.get(i, j));
            }
        }
        return result;
    }

    private static DoubleMatrix doubleCopyOf(FloatMatrix input) {
        DoubleMatrix result = DoubleMatrix.zeros(input.rows(), input.cols());
        for (int j = 0; j < input.cols(); j++) {
            for (int i = 0; i < input.rows(); i++) {
                result.set(i, j, input.get(i, j));
            }
        }
        return result;
    }

    private static void assertMatchesReference(DoubleMatrix input, DoubleMatrix actual) {
        int p = input.cols();
        for (int a = 0; a < p; a++) {
            for (int b = 0; b < p; b++) {
                double expected = a == b ? 1.0 : naiveKendallTau(column(input, a), column(input, b));
                assertEquals(expected, actual.get(a, b), 1e-12, "mismatch at [" + a + "," + b + "]");
            }
        }
    }

    private static double[] column(DoubleMatrix matrix, int col) {
        double[] values = new double[matrix.rows()];
        for (int i = 0; i < values.length; i++) {
            values[i] = matrix.get(i, col);
        }
        return values;
    }

    /**
     * Independent oracle: tau-b by direct O(n^2) enumeration of every pair,
     * a different algorithm from the merge-sort inversion count under test.
     */
    private static double naiveKendallTau(double[] x, double[] y) {
        int n = x.length;
        long n0 = (long) n * (n - 1) / 2;
        long concordant = 0;
        long discordant = 0;
        long tiedX = 0;
        long tiedY = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int dx = Double.compare(x[i], x[j]);
                int dy = Double.compare(y[i], y[j]);
                if (dx == 0) {
                    tiedX++;
                }
                if (dy == 0) {
                    tiedY++;
                }
                if (dx != 0 && dy != 0) {
                    if ((dx > 0) == (dy > 0)) {
                        concordant++;
                    } else {
                        discordant++;
                    }
                }
            }
        }
        double denominator = Math.sqrt((double) (n0 - tiedX) * (double) (n0 - tiedY));
        return denominator == 0 ? Double.NaN : (concordant - discordant) / denominator;
    }
}
