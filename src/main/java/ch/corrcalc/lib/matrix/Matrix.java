package ch.corrcalc.lib.matrix;

import ch.corrcalc.lib.exception.InvalidInputException;

import java.util.Arrays;

/**
 * A dense matrix of doubles backed by a single flat array in <b>column-major</b> order.
 * <p>
 * Column-major storage is chosen deliberately: every statistical operation in this
 * library (column means, standard deviations, column dot products) scans one column
 * at a time, and with this layout each column occupies a contiguous memory block.
 * That keeps the hot loops sequential, cache-friendly and free of per-row object
 * overhead.
 * <p>
 * The class is a thin, allocation-free wrapper: factory methods that accept an
 * existing column-major array take ownership of it without copying. For the same
 * reason {@link #data()} exposes the live backing array so that calculators and
 * preparers in this library can avoid defensive copies. Callers that mutate the
 * returned array mutate the matrix.
 */
public final class Matrix {

    private final double[] data;
    private final int rows;
    private final int cols;

    private Matrix(double[] data, int rows, int cols) {
        this.data = data;
        this.rows = rows;
        this.cols = cols;
    }

    /**
     * Wraps an existing column-major array without copying. The matrix takes
     * ownership of the array; the caller must not modify it afterwards.
     *
     * @param data values in column-major order, exactly {@code rows * cols} long
     * @param rows number of rows, zero or more
     * @param cols number of columns, zero or more
     * @return a matrix backed directly by {@code data}
     */
    public static Matrix columnMajor(double[] data, int rows, int cols) {
        if (data == null) {
            throw new InvalidInputException("Matrix data must not be null");
        }
        validateDimensions(rows, cols);
        if (data.length != (long) rows * cols) {
            throw new InvalidInputException(
                    "Matrix data length [" + data.length + "] does not match dimensions [" + rows + "x" + cols + "]");
        }
        return new Matrix(data, rows, cols);
    }

    /**
     * Creates a zero-filled matrix of the given dimensions.
     */
    public static Matrix zeros(int rows, int cols) {
        validateDimensions(rows, cols);
        return new Matrix(new double[Math.multiplyExact(rows, cols)], rows, cols);
    }

    /**
     * Copies row-oriented data (as it usually arrives from external sources)
     * into the internal column-major layout.
     *
     * @param rowData one array per row, all of equal length
     */
    public static Matrix fromRows(double[][] rowData) {
        if (rowData == null) {
            throw new InvalidInputException("Matrix data must not be null");
        }
        int numRows = rowData.length;
        int numCols = numRows == 0 ? 0 : rowData[0].length;
        double[] data = new double[Math.multiplyExact(numRows, numCols)];
        for (int i = 0; i < numRows; i++) {
            double[] row = rowData[i];
            if (row == null || row.length != numCols) {
                throw new InvalidInputException("All rows must have the same length [" + numCols + "], "
                        + "row [" + i + "] has [" + (row == null ? "null" : row.length) + "]");
            }
            for (int j = 0; j < numCols; j++) {
                data[j * numRows + i] = row[j];
            }
        }
        return new Matrix(data, numRows, numCols);
    }

    private static void validateDimensions(int rows, int cols) {
        if (rows < 0 || cols < 0) {
            throw new InvalidInputException("Matrix dimensions must not be negative: [" + rows + "x" + cols + "]");
        }
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public double get(int row, int col) {
        checkIndex(row, col);
        return data[col * rows + row];
    }

    public void set(int row, int col, double value) {
        checkIndex(row, col);
        data[col * rows + row] = value;
    }

    private void checkIndex(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new IndexOutOfBoundsException(
                    "Index [" + row + "," + col + "] out of bounds for matrix [" + rows + "x" + cols + "]");
        }
    }

    /**
     * The live column-major backing array — element {@code (row, col)} lives at
     * {@code col * rows() + row}. Exposed without copying so hot loops can run
     * directly over it; treat it as read-only unless you own the matrix.
     */
    public double[] data() {
        return data;
    }

    /**
     * Copies the matrix into a row-oriented {@code double[rows][cols]} array,
     * the layout most convenient for interoperability and display.
     */
    public double[][] toRowArrays() {
        double[][] result = new double[rows][cols];
        for (int j = 0; j < cols; j++) {
            int offset = j * rows;
            for (int i = 0; i < rows; i++) {
                result[i][j] = data[offset + i];
            }
        }
        return result;
    }

    /**
     * Returns a deep copy backed by its own array.
     */
    public Matrix copy() {
        return new Matrix(Arrays.copyOf(data, data.length), rows, cols);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Matrix other)) {
            return false;
        }
        return rows == other.rows && cols == other.cols && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * rows + cols) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Matrix[" + rows + "x" + cols + "]";
    }
}
