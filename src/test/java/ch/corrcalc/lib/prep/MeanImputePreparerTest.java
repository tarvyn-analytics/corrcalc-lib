package ch.corrcalc.lib.prep;

import ch.corrcalc.lib.exception.InvalidInputException;
import ch.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeanImputePreparerTest {

    private final DataPreparer preparer = Preparers.imputeMean();

    @Test
    void prepare_MissingValues_AreReplacedByColumnMean() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 10},
                {Double.NaN, 20},
                {3, Double.NaN}
        });
        DoubleMatrix result = preparer.prepare(input);

        assertEquals(2.0, result.get(1, 0), 1e-12);
        assertEquals(15.0, result.get(2, 1), 1e-12);
        // present values stay untouched
        assertEquals(1.0, result.get(0, 0), 0.0);
        assertEquals(20.0, result.get(1, 1), 0.0);
    }

    @Test
    void prepare_NoMissingValues_ReturnsEqualMatrix() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {3, 4}
        });

        assertEquals(input, preparer.prepare(input));
    }

    @Test
    void prepare_AllNaNColumn_ThrowsInvalidInput() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, Double.NaN},
                {2, Double.NaN}
        });

        assertThrows(InvalidInputException.class, () -> preparer.prepare(input));
    }

    @Test
    void prepare_InputMatrix_IsNotModified() {
        DoubleMatrix input = DoubleMatrix.fromRows(new double[][]{
                {1, 2},
                {Double.NaN, 4}
        });
        DoubleMatrix snapshot = input.copy();

        preparer.prepare(input);

        assertEquals(snapshot, input);
    }
}
