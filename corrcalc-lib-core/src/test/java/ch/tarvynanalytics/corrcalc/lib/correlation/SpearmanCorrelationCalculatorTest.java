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

class SpearmanCorrelationCalculatorTest {

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_StrictlyMonotonicNonlinear_ReturnsCorrelationOne(Profile profile) {
        // y = x^3 is a perfect monotonic but non-linear relationship: Spearman
        // is 1 even though Pearson would be below 1
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 1},
                {2, 8},
                {3, 27},
                {4, 64},
                {5, 125}
        });

        double[][] result = calculator(profile).calculate(input).toRowArrays();

        assertEquals(1.0, result[0][1], 1e-12);
        assertEquals(1.0, result[1][0], 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_StrictlyMonotonicDecreasing_ReturnsCorrelationMinusOne(Profile profile) {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 125},
                {2, 64},
                {3, 27},
                {4, 8},
                {5, 1}
        });

        assertEquals(-1.0, calculator(profile).calculate(input).get(0, 1), 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_RandomSmallMatrix_MatchesRankThenPearsonOracle(Profile profile) {
        DoubleMatrix input = randomMatrix(45, 6, 42L);

        assertMatchesReference(input, calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_DataWithTies_MatchesRankThenPearsonOracle(Profile profile) {
        // small integer range forces many ties, exercising the averaged ranks
        Random random = new Random(99L);
        DoubleMatrix input = DoubleMatrix.zeros(60, 5);
        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 60; i++) {
                input.set(i, j, random.nextInt(8));
            }
        }

        assertMatchesReference(input, calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_LargeMatrixAboveParallelThreshold_MatchesRankThenPearsonOracle(Profile profile) {
        int n = 250;
        int p = 50;
        assertTrue((long) n * p * p >= PearsonCorrelationCalculator.PARALLEL_THRESHOLD_FLOPS,
                "test matrix must be large enough to exercise the parallel Pearson path");
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
        DoubleMatrix input = randomMatrix(20, 4, 99L);
        DoubleMatrix snapshot = input.copy();

        calculator(profile).calculate(input);

        assertEquals(snapshot, input);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_FloatRandomMatrix_MatchesDoubleResultWithinFloatPrecision(Profile profile) {
        DoubleMatrix input = randomMatrix(45, 6, 24L);
        FloatMatrix floatInput = floatCopyOf(input);

        DoubleMatrix expected = calculator(profile).calculate(input);
        FloatMatrix actual = calculator(profile).calculate(floatInput);

        for (int i = 0; i < expected.rows(); i++) {
            for (int j = 0; j < expected.cols(); j++) {
                assertEquals(expected.get(i, j), actual.get(i, j), 1e-5,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculateToDouble_FloatRandomMatrix_MatchesRankThenPearsonOracle(Profile profile) {
        DoubleMatrix input = randomMatrix(257, 7, 4242L);
        FloatMatrix floatInput = floatCopyOf(input);

        DoubleMatrix actual = calculator(profile).calculateToDouble(floatInput);

        // ranks are identical whether read from the float or the double copy, so
        // the double oracle applies, with the tolerance covering Pearson's float
        // normalized working buffer -- the same 5e-8 quantization budget as in
        // the Pearson calculateToDouble test
        assertMatchesReference(doubleCopyOf(floatInput), actual, 5e-8);
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

    private static SpearmanCorrelationCalculator calculator(Profile profile) {
        return (SpearmanCorrelationCalculator) Correlations.spearman(profile);
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
        assertMatchesReference(input, actual, 1e-9);
    }

    private static void assertMatchesReference(DoubleMatrix input, DoubleMatrix actual, double tolerance) {
        double[][] expected = referenceSpearman(input);
        for (int i = 0; i < expected.length; i++) {
            for (int j = 0; j < expected.length; j++) {
                assertEquals(expected[i][j], actual.get(i, j), tolerance,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
    }

    /**
     * Independent oracle: rank each column by counting (a different algorithm
     * from the merge sort under test), then take the naive Pearson correlation
     * of the ranks.
     */
    private static double[][] referenceSpearman(DoubleMatrix input) {
        int n = input.rows();
        int p = input.cols();
        double[][] ranks = new double[p][];
        for (int j = 0; j < p; j++) {
            double[] column = new double[n];
            for (int i = 0; i < n; i++) {
                column[i] = input.get(i, j);
            }
            ranks[j] = averageRanksByCounting(column);
        }
        double[][] corr = new double[p][p];
        for (int a = 0; a < p; a++) {
            corr[a][a] = 1.0;
            for (int b = 0; b < a; b++) {
                double r = naivePearson(ranks[a], ranks[b]);
                corr[a][b] = r;
                corr[b][a] = r;
            }
        }
        return corr;
    }

    private static double[] averageRanksByCounting(double[] values) {
        int n = values.length;
        double[] ranks = new double[n];
        for (int i = 0; i < n; i++) {
            int less = 0;
            int equal = 0;
            for (double value : values) {
                if (value < values[i]) {
                    less++;
                } else if (value == values[i]) {
                    equal++;
                }
            }
            ranks[i] = less + (equal + 1) / 2.0;
        }
        return ranks;
    }

    private static double naivePearson(double[] x, double[] y) {
        int n = x.length;
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;
        double cov = 0;
        double varX = 0;
        double varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        return cov / Math.sqrt(varX * varY);
    }
}
