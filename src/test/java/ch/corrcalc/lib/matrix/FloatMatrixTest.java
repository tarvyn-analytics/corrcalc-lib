package ch.corrcalc.lib.matrix;

import ch.corrcalc.lib.exception.InvalidInputException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FloatMatrixTest {

    @Test
    void fromRows_RowOrientedInput_StoresValuesColumnMajor() {
        FloatMatrix matrix = FloatMatrix.fromRows(new float[][]{
                {1, 2, 3},
                {4, 5, 6}
        });

        assertEquals(2, matrix.rows());
        assertEquals(3, matrix.cols());
        assertArrayEquals(new float[]{1, 4, 2, 5, 3, 6}, matrix.data(), 0.0f);
        assertEquals(1, matrix.get(0, 0), 0.0f);
        assertEquals(6, matrix.get(1, 2), 0.0f);
    }

    @Test
    void fromRows_RaggedRows_ThrowsInvalidInput() {
        float[][] ragged = {
                {1, 2},
                {3}
        };
        assertThrows(InvalidInputException.class, () -> FloatMatrix.fromRows(ragged));
    }

    @Test
    void fromRows_NullRow_ThrowsInvalidInput() {
        float[][] withNullRow = {
                {1, 2},
                null
        };
        assertThrows(InvalidInputException.class, () -> FloatMatrix.fromRows(withNullRow));
    }

    @Test
    void fromRows_NullInput_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> FloatMatrix.fromRows(null));
    }

    @Test
    void fromRows_EmptyInput_CreatesZeroByZeroMatrix() {
        FloatMatrix matrix = FloatMatrix.fromRows(new float[][]{});

        assertEquals(0, matrix.rows());
        assertEquals(0, matrix.cols());
        assertEquals(0, matrix.data().length);
    }

    @Test
    void columnMajor_MatchingLength_WrapsWithoutCopy() {
        float[] data = {1, 2, 3, 4, 5, 6};
        FloatMatrix matrix = FloatMatrix.columnMajor(data, 3, 2);

        assertSame(data, matrix.data());
        assertEquals(2, matrix.get(1, 0), 0.0f);
        assertEquals(4, matrix.get(0, 1), 0.0f);
    }

    @Test
    void columnMajor_LengthMismatch_ThrowsInvalidInput() {
        float[] data = new float[5];
        assertThrows(InvalidInputException.class, () -> FloatMatrix.columnMajor(data, 3, 2));
    }

    @Test
    void columnMajor_NullData_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> FloatMatrix.columnMajor(null, 1, 1));
    }

    @Test
    void columnMajor_NegativeDimensions_ThrowsInvalidInput() {
        float[] empty = new float[0];
        assertThrows(InvalidInputException.class, () -> FloatMatrix.columnMajor(empty, -1, 0));
        assertThrows(InvalidInputException.class, () -> FloatMatrix.columnMajor(empty, 0, -1));
    }

    @Test
    void zeros_ValidDimensions_CreatesZeroFilledMatrix() {
        FloatMatrix matrix = FloatMatrix.zeros(2, 3);

        assertEquals(2, matrix.rows());
        assertEquals(3, matrix.cols());
        assertArrayEquals(new float[6], matrix.data(), 0.0f);
    }

    @Test
    void zeros_NegativeDimensions_ThrowsInvalidInput() {
        assertThrows(InvalidInputException.class, () -> FloatMatrix.zeros(-1, 2));
    }

    @Test
    void set_ValidIndex_UpdatesValue() {
        FloatMatrix matrix = FloatMatrix.zeros(2, 2);
        matrix.set(1, 0, 7.5f);

        assertEquals(7.5f, matrix.get(1, 0), 0.0f);
    }

    @Test
    void get_IndexOutOfBounds_ThrowsIndexOutOfBounds() {
        FloatMatrix matrix = FloatMatrix.zeros(2, 2);

        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.get(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.set(0, -1, 1.0f));
    }

    @Test
    void toRowArrays_ColumnMajorData_RestoresRowOrientation() {
        float[][] rows = {
                {1, 2, 3},
                {4, 5, 6}
        };
        FloatMatrix matrix = FloatMatrix.fromRows(rows);

        float[][] result = matrix.toRowArrays();

        assertArrayEquals(rows[0], result[0], 0.0f);
        assertArrayEquals(rows[1], result[1], 0.0f);
    }

    @Test
    void copy_Original_IsIndependentOfOriginal() {
        FloatMatrix original = FloatMatrix.fromRows(new float[][]{{1, 2}, {3, 4}});
        FloatMatrix copy = original.copy();

        copy.set(0, 0, 99);

        assertNotSame(original.data(), copy.data());
        assertEquals(1, original.get(0, 0), 0.0f);
        assertEquals(99, copy.get(0, 0), 0.0f);
    }

    @Test
    void equals_SameContent_AreEqualWithSameHashCode() {
        FloatMatrix first = FloatMatrix.fromRows(new float[][]{{1, 2}, {3, 4}});
        FloatMatrix second = FloatMatrix.columnMajor(new float[]{1, 3, 2, 4}, 2, 2);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void equals_DifferentContentShapeOrElementType_AreNotEqual() {
        FloatMatrix base = FloatMatrix.fromRows(new float[][]{{1, 2}, {3, 4}});

        assertNotEquals(FloatMatrix.fromRows(new float[][]{{1, 2}, {3, 5}}), base);
        assertNotEquals(FloatMatrix.columnMajor(new float[]{1, 3, 2, 4}, 4, 1), base);
        // cross-type comparison runs in both directions on purpose: each matrix
        // type's equals must reject the other element type as the receiver
        assertNotEquals(DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}}), base);
        assertNotEquals(base, DoubleMatrix.fromRows(new double[][]{{1, 2}, {3, 4}}));
    }

    @Test
    void toString_AnyMatrix_ContainsTypeAndDimensions() {
        assertEquals("FloatMatrix[2x3]", FloatMatrix.zeros(2, 3).toString());
    }
}
