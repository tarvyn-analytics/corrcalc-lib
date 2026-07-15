package ch.tarvynanalytics.corrcalc.lib.prep;

import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class DropMissingRowsPreparerTest {

    private final DataPreparer preparer = Preparers.dropMissingRows();

    @Test
    void prepare_RowsWithNaN_AreRemoved() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {Double.NaN, 4},
                {5, 6},
                {7, Double.NaN}
        });
        DoubleMatrix result = preparer.prepare(input);

        assertEquals(DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {5, 6}
        }), result);
    }

    @Test
    void prepare_NoMissingValues_ReturnsEqualIndependentCopy() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {3, 4}
        });
        DoubleMatrix result = preparer.prepare(input);

        assertEquals(input, result);
        assertNotSame(input.data(), result.data());
    }

    @Test
    void prepare_AllRowsMissing_ReturnsZeroRowMatrix() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {Double.NaN, 2},
                {3, Double.NaN}
        });
        DoubleMatrix result = preparer.prepare(input);

        assertEquals(0, result.rows());
        assertEquals(2, result.cols());
    }

    @Test
    void prepare_InputMatrix_IsNotModified() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, Double.NaN},
                {3, 4}
        });
        DoubleMatrix snapshot = input.copy();

        preparer.prepare(input);

        assertEquals(snapshot, input);
    }
}
