package ch.corrcalc.lib.prep;

import ch.corrcalc.lib.exception.InvalidInputException;
import ch.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CenterPreparerTest {

    private final DataPreparer preparer = Preparers.center();

    @Test
    void prepare_AnyColumns_HaveZeroMeanAfterCentering() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 100},
                {2, 200},
                {6, 600}
        });
        DoubleMatrix result = preparer.prepare(input);

        for (int j = 0; j < result.cols(); j++) {
            double sum = 0;
            for (int i = 0; i < result.rows(); i++) {
                sum += result.get(i, j);
            }
            assertEquals(0.0, sum / result.rows(), 1e-12);
        }
        assertEquals(-2.0, result.get(0, 0), 1e-12);
        assertEquals(300.0, result.get(2, 1), 1e-12);
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
