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

class PartialCorrelationCalculatorTest {

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_ThreeColumns_MatchesFirstOrderPartialFormula(Profile profile) {
        // first-order partial correlation has the closed form
        // rho(0,1|2) = (r01 - r02*r12) / sqrt((1 - r02^2)(1 - r12^2))
        DoubleMatrix input = randomMatrix(60, 3, 11L);
        double[] x0 = column(input, 0);
        double[] x1 = column(input, 1);
        double[] x2 = column(input, 2);
        double r01 = naivePearson(x0, x1);
        double r02 = naivePearson(x0, x2);
        double r12 = naivePearson(x1, x2);
        double expected = (r01 - r02 * r12) / Math.sqrt((1 - r02 * r02) * (1 - r12 * r12));

        double[][] result = calculator(profile).calculate(input).toRowArrays();

        assertEquals(expected, result[0][1], 1e-12);
        assertEquals(expected, result[1][0], 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_TwoColumns_EqualsPearsonCorrelation(Profile profile) {
        // with no other variables to control for, partial correlation is the
        // ordinary Pearson correlation
        DoubleMatrix input = randomMatrix(40, 2, 5L);

        double partial = calculator(profile).calculate(input).get(0, 1);
        double pearson = Correlations.pearson(profile).calculate(input).get(0, 1);

        assertEquals(pearson, partial, 1e-12);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_RandomSmallMatrix_MatchesResidualRegressionOracle(Profile profile) {
        DoubleMatrix input = randomMatrix(50, 6, 42L);

        assertMatchesReference(input, calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_LargeMatrixAboveParallelThreshold_MatchesResidualRegressionOracle(Profile profile) {
        int n = 1100;
        int p = 16;
        assertTrue((long) n * p * p >= PearsonCorrelationCalculator.PARALLEL_THRESHOLD_FLOPS,
                "test matrix must be large enough to exercise the parallel Pearson path");
        DoubleMatrix input = randomMatrix(n, p, 4242L);

        assertMatchesReference(input, calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_RandomMatrix_ResultIsSymmetricWithUnitDiagonalAndBoundedValues(Profile profile) {
        DoubleMatrix result = calculator(profile).calculate(randomMatrix(80, 9, 7L));

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
    void calculate_SingleColumn_ReturnsSingleOne(Profile profile) {
        DoubleMatrix input = randomMatrix(10, 1, 3L);
        DoubleMatrix result = calculator(profile).calculate(input);

        assertEquals(1, result.rows());
        assertEquals(1, result.cols());
        assertEquals(1.0, result.get(0, 0), 0.0);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_ConstantColumn_PropagatesNaNWithUnitDiagonal(Profile profile) {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 5, 2},
                {2, 5, 1},
                {3, 5, 4},
                {4, 5, 3}
        });

        double[][] result = calculator(profile).calculate(input).toRowArrays();

        for (int i = 0; i < 3; i++) {
            assertEquals(1.0, result[i][i], 0.0);
            for (int j = 0; j < 3; j++) {
                if (i != j) {
                    assertTrue(Double.isNaN(result[i][j]), "expected NaN at [" + i + "," + j + "]");
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_CollinearColumns_ThrowsInvalidInput(Profile profile) {
        // column 2 = column 0 + column 1, so the correlation matrix is singular
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 4, 5},
                {2, 1, 3},
                {3, 5, 8},
                {7, 2, 9},
                {4, 6, 10}
        });

        assertThrows(InvalidInputException.class, () -> calculator(profile).calculate(input));
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_FewerObservationsThanVariables_ThrowsInvalidInput(Profile profile) {
        // 3 observations, 4 variables -> the correlation matrix cannot be full rank
        DoubleMatrix input = randomMatrix(3, 4, 1L);

        assertThrows(InvalidInputException.class, () -> calculator(profile).calculate(input));
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
    void calculate_IllConditionedColumns_MatchesReferenceWithinTolerance(Profile profile) {
        // one column is almost a linear combination of two others: R is highly
        // ill-conditioned but still positive definite, the worst case for the
        // inversion's accuracy
        Random random = new Random(123L);
        int n = 200;
        DoubleMatrix input = DoubleMatrix.zeros(n, 4);
        for (int i = 0; i < n; i++) {
            double a = random.nextGaussian();
            double b = random.nextGaussian();
            double c = random.nextGaussian();
            input.set(i, 0, a);
            input.set(i, 1, b);
            input.set(i, 2, c);
            input.set(i, 3, a + b + 1e-3 * random.nextGaussian());
        }

        double[][] expected = referencePartial(input);
        DoubleMatrix actual = calculator(profile).calculate(input);
        for (int i = 0; i < expected.length; i++) {
            for (int j = 0; j < expected.length; j++) {
                assertEquals(expected[i][j], actual.get(i, j), 1e-6,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculate_FloatRandomMatrix_MatchesDoubleResultWithinFloatPrecision(Profile profile) {
        DoubleMatrix input = randomMatrix(120, 6, 24L);
        FloatMatrix floatInput = floatCopyOf(input);

        DoubleMatrix expected = calculator(profile).calculate(input);
        FloatMatrix actual = calculator(profile).calculate(floatInput);

        for (int i = 0; i < expected.rows(); i++) {
            for (int j = 0; j < expected.cols(); j++) {
                assertEquals(expected.get(i, j), actual.get(i, j), 1e-4,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void calculateToDouble_FloatRandomMatrix_MatchesResidualRegressionOracle(Profile profile) {
        DoubleMatrix input = randomMatrix(257, 6, 4242L);
        FloatMatrix floatInput = floatCopyOf(input);
        DoubleMatrix quantized = doubleCopyOf(floatInput);

        DoubleMatrix actual = calculator(profile).calculateToDouble(floatInput);

        double[][] expected = referencePartial(quantized);
        for (int i = 0; i < expected.length; i++) {
            for (int j = 0; j < expected.length; j++) {
                assertEquals(expected[i][j], actual.get(i, j), 1e-4,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
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

    private static PartialCorrelationCalculator calculator(Profile profile) {
        return (PartialCorrelationCalculator) Correlations.partial(profile);
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

    private static double[] column(DoubleMatrix matrix, int col) {
        double[] values = new double[matrix.rows()];
        for (int i = 0; i < values.length; i++) {
            values[i] = matrix.get(i, col);
        }
        return values;
    }

    private static void assertMatchesReference(DoubleMatrix input, DoubleMatrix actual) {
        double[][] expected = referencePartial(input);
        for (int i = 0; i < expected.length; i++) {
            for (int j = 0; j < expected.length; j++) {
                assertEquals(expected[i][j], actual.get(i, j), 1e-9,
                        "mismatch at [" + i + "," + j + "]");
            }
        }
    }

    /**
     * Independent oracle: partial correlation by residual regression. For each
     * pair, regress both variables on all the others (with an intercept) by
     * ordinary least squares and correlate the residuals — a different algorithm
     * from the precision-matrix route under test.
     */
    private static double[][] referencePartial(DoubleMatrix input) {
        int p = input.cols();
        double[][] partial = new double[p][p];
        for (int a = 0; a < p; a++) {
            partial[a][a] = 1.0;
            for (int b = 0; b < a; b++) {
                double[] residualsA = residualsControllingForRest(input, a, b);
                double[] residualsB = residualsControllingForRest(input, b, a);
                double r = naivePearson(residualsA, residualsB);
                partial[a][b] = r;
                partial[b][a] = r;
            }
        }
        return partial;
    }

    /** OLS residuals of column {@code target} regressed on every column except
     *  {@code target} and {@code other}, plus an intercept. */
    private static double[] residualsControllingForRest(DoubleMatrix input, int target, int other) {
        int n = input.rows();
        int p = input.cols();
        int predictors = p - 2 < 0 ? 0 : p - 2;
        int cols = predictors + 1; // + intercept
        double[][] design = new double[n][cols];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            design[i][0] = 1.0;
            int c = 1;
            for (int j = 0; j < p; j++) {
                if (j != target && j != other) {
                    design[i][c++] = input.get(i, j);
                }
            }
            y[i] = input.get(i, target);
        }
        double[][] normal = new double[cols][cols];
        double[] rhs = new double[cols];
        for (int r = 0; r < cols; r++) {
            for (int c = 0; c < cols; c++) {
                double s = 0;
                for (int i = 0; i < n; i++) {
                    s += design[i][r] * design[i][c];
                }
                normal[r][c] = s;
            }
            double s = 0;
            for (int i = 0; i < n; i++) {
                s += design[i][r] * y[i];
            }
            rhs[r] = s;
        }
        double[] beta = solve(normal, rhs);
        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            double fitted = 0;
            for (int c = 0; c < cols; c++) {
                fitted += design[i][c] * beta[c];
            }
            residuals[i] = y[i] - fitted;
        }
        return residuals;
    }

    /** Gaussian elimination with partial pivoting for a small dense system. */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double factor = m[r][col] / m[col][col];
                for (int c = col; c <= n; c++) {
                    m[r][c] -= factor * m[col][c];
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = m[i][n] / m[i][i];
        }
        return x;
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
