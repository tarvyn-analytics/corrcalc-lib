package ch.tarvynanalytics.corrcalc.lib.prep;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandardizePreparerTest {

    private final DataPreparer preparer = Preparers.standardize();

    @Test
    void prepare_AnyColumns_HaveZeroMeanAndUnitSampleVariance() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 100},
                {2, 250},
                {3, 400},
                {4, 550}
        });
        DoubleMatrix result = preparer.prepare(input);

        int n = result.rows();
        for (int j = 0; j < result.cols(); j++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += result.get(i, j);
            }
            double mean = sum / n;
            assertEquals(0.0, mean, 1e-12);

            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double c = result.get(i, j) - mean;
                sumSq += c * c;
            }
            assertEquals(1.0, sumSq / (n - 1), 1e-12);
        }
    }

    @Test
    void prepare_ZeroVarianceColumn_BecomesAllZeros() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {5, 1},
                {5, 2},
                {5, 3}
        });
        DoubleMatrix result = preparer.prepare(input);

        for (int i = 0; i < result.rows(); i++) {
            assertEquals(0.0, result.get(i, 0), 0.0);
        }
    }

    @Test
    void prepare_SingleRow_BecomesZeroWithoutDividingByZero() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{{7, -3}});
        DoubleMatrix result = preparer.prepare(input);

        assertEquals(0.0, result.get(0, 0), 0.0);
        assertEquals(0.0, result.get(0, 1), 0.0);
    }

    @Test
    void prepare_ZeroRowMatrix_ThrowsInvalidInput() {
        DoubleMatrix empty = DoubleMatrix.columnMajor(new double[0], 0, 2);

        assertThrows(InvalidInputException.class, () -> preparer.prepare(empty));
    }

    @Test
    void prepare_InputMatrix_IsNotModified() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}});
        DoubleMatrix snapshot = input.copy();

        preparer.prepare(input);

        assertEquals(snapshot, input);
    }
}
