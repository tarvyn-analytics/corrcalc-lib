package ch.corrcalc.lib.matrix;

import ch.corrcalc.lib.exception.InvalidInputException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DoubleMatrixTest {

    @Test
    void fromRows_RowOrientedInput_StoresValuesColumnMajor() {
        DoubleMatrix matrix = DoubleMatrix.fromRows(new double[][]{
                {1, 2, 3},
                {4, 5, 6}
        });

        assertEquals(2, matrix.rows());
        assertEquals(3, matrix.cols());
        assertArrayEquals(new double[]{1, 4, 2, 5, 3, 6}, matrix.data(), 0.0);
        assertEquals(1, matrix.get(0, 0), 0.0);
        assertEquals(6, matrix.get(1, 2), 0.0);
    }

    @Test
    void fromRows_RaggedRows_ThrowsInvalidInput() {
        double[][] ragged = {
                {1, 2},
                {3}
        };
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.fromRows(ragged));
    }

    @Test
    void fromRows_NullRow_ThrowsInvalidInput() {
        double[][] withNullRow = {
                {1, 2},
                null
        };
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.fromRows(withNullRow));
    }

    @Test
    void fromRows_NullInput_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.fromRows(null));
    }

    @Test
    void fromRows_EmptyInput_CreatesZeroByZeroMatrix() {
        DoubleMatrix matrix = DoubleMatrix.fromRows(new double[][]{});

        assertEquals(0, matrix.rows());
        assertEquals(0, matrix.cols());
        assertEquals(0, matrix.data().length);
    }

    @Test
    void columnMajor_MatchingLength_WrapsWithoutCopy() {
        double[] data = {1, 2, 3, 4, 5, 6};
        DoubleMatrix matrix = DoubleMatrix.columnMajor(data, 3, 2);

        assertSame(data, matrix.data());
        assertEquals(2, matrix.get(1, 0), 0.0);
        assertEquals(4, matrix.get(0, 1), 0.0);
    }

    @Test
    void columnMajor_LengthMismatch_ThrowsInvalidInput() {
        double[] data = new double[5];
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.columnMajor(data, 3, 2));
    }

    @Test
    void columnMajor_NullData_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.columnMajor(null, 1, 1));
    }

    @Test
    void columnMajor_NegativeDimensions_ThrowsInvalidInput() {
        double[] empty = new double[0];
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.columnMajor(empty, -1, 0));
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.columnMajor(empty, 0, -1));
    }

    @Test
    void zeros_ValidDimensions_CreatesZeroFilledMatrix() {
        DoubleMatrix matrix = DoubleMatrix.zeros(2, 3);

        assertEquals(2, matrix.rows());
        assertEquals(3, matrix.cols());
        assertArrayEquals(new double[6], matrix.data(), 0.0);
    }

    @Test
    void zeros_NegativeDimensions_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> DoubleMatrix.zeros(-1, 2));
    }

    @Test
    void set_ValidIndex_UpdatesValue() {
        DoubleMatrix matrix = DoubleMatrix.zeros(2, 2);
        matrix.set(1, 0, 7.5);

        assertEquals(7.5, matrix.get(1, 0), 0.0);
    }

    @Test
    void get_IndexOutOfBounds_ThrowsIndexOutOfBounds() {
        DoubleMatrix matrix = DoubleMatrix.zeros(2, 2);

        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.set(0, -1, 1.0));
    }

    @Test
    void toRowArrays_ColumnMajorData_RestoresRowOrientation() {
        double[][] rows = {
                {1, 2, 3},
                {4, 5, 6}
        };
        DoubleMatrix matrix = DoubleMatrix.fromRows(rows);

        double[][] result = matrix.toRowArrays();

        assertArrayEquals(rows[0], result[0], 0.0);
        assertArrayEquals(rows[1], result[1], 0.0);
    }

    @Test
    void copy_Original_IsIndependentOfOriginal() {
        DoubleMatrix original = DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}});
        DoubleMatrix copy = original.copy();

        copy.set(0, 0, 99);

        assertNotSame(original.data(), copy.data());
        assertEquals(1, original.get(0, 0), 0.0);
        assertEquals(99, copy.get(0, 0), 0.0);
    }

    @Test
    void equals_SameContent_AreEqualWithSameHashCode() {
        DoubleMatrix first = DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}});
        DoubleMatrix second = DoubleMatrix.columnMajor(new double[]{1, 3, 2, 4}, 2, 2);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void equals_DifferentContentOrShape_AreNotEqual() {
        DoubleMatrix base = DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}});

        assertNotEquals(DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 5}}), base);
        assertNotEquals(DoubleMatrix.columnMajor(new double[]{1, 3, 2, 4}, 4, 1), base);
        assertNotEquals("not a matrix", base);
    }

    @Test
    void toString_AnyMatrix_ContainsDimensions() {
        assertEquals("DoubleMatrix[2x3]", DoubleMatrix.zeros(2, 3).toString());
    }
}
