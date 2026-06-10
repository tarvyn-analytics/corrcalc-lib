package ch.corrcalc.lib.matrix;

import ch.corrcalc.lib.exception.InvalidInputException;

/**
 * Shared shape and bounds logic for the dense column-major matrix types
 * ({@link DoubleMatrix} for double precision, {@link FloatMatrix} for single
 * precision). Only the element storage differs between the subclasses.
 */
abstract class AbstractMatrix {

    final int rows;
    final int cols;

    AbstractMatrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
    }

    public final int rows() {
        return rows;
    }

    public final int cols() {
        return cols;
    }

    final void checkIndex(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new IndexOutOfBoundsException(
                    "Index [" + row + "," + col + "] out of bounds for matrix [" + rows + "x" + cols + "]");
        }
    }

    static void validateDimensions(int rows, int cols) {
        if (rows < 0 || cols < 0) {
            throw new InvalidInputException("Matrix dimensions must not be negative: [" + rows + "x" + cols + "]");
        }
    }

    static void validateDataLength(int dataLength, int rows, int cols) {
        if (dataLength != (long) rows * cols) {
            throw new InvalidInputException(
                    "Matrix data length [" + dataLength + "] does not match dimensions [" + rows + "x" + cols + "]");
        }
    }

    static int checkedLength(int rows, int cols) {
        return Math.multiplyExact(rows, cols);
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + "[" + rows + "x" + cols + "]";
    }
}
