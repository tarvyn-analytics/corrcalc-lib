package ch.tarvynanalytics.corrcalc.lib.matrix;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;

import java.util.Arrays;

/**
 * The single-precision variant of {@link DoubleMatrix}: a dense matrix of floats
 * backed by a single flat array in <b>column-major</b> order.
 * <p>
 * It halves the memory footprint and data transfer of {@link DoubleMatrix} at the
 * cost of ~7 significant decimal digits per element, which is plenty for
 * correlation work on measured data. Calculators still accumulate all sums in
 * double precision, so only the storage — not the arithmetic — is single
 * precision.
 * <p>
 * The storage, ownership and zero-copy semantics are identical to
 * {@link DoubleMatrix}; see there for the rationale.
 */
public final class FloatMatrix extends AbstractMatrix {

    private final float[] data;

    private FloatMatrix(float[] data, int rows, int cols) {
        super(rows, cols);
        this.data = data;
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
    public static FloatMatrix columnMajor(float[] data, int rows, int cols) {
        if (data == null) {
            throw new InvalidInputException("Matrix data must not be null");
        }
        validateDimensions(rows, cols);
        validateDataLength(data.length, rows, cols);
        return new FloatMatrix(data, rows, cols);
    }

    /**
     * Creates a zero-filled matrix of the given dimensions.
     */
    public static FloatMatrix zeros(int rows, int cols) {
        validateDimensions(rows, cols);
        return new FloatMatrix(new float[checkedLength(rows, cols)], rows, cols);
    }

    /**
     * Copies row-oriented data (as it usually arrives from external sources)
     * into the internal column-major layout.
     *
     * @param rowData one array per row, all of equal length
     */
    public static FloatMatrix fromRows(float[][] rowData) {
        if (rowData == null) {
            throw new InvalidInputException("Matrix data must not be null");
        }
        int numRows = rowData.length;
        int numCols = numRows == 0 ? 0 : rowData[0].length;
        float[] data = new float[checkedLength(numRows, numCols)];
        for (int i = 0; i < numRows; i++) {
            float[] row = rowData[i];
            if (row == null || row.length != numCols) {
                throw new InvalidInputException("All rows must have the same length [" + numCols + "], "
                        + "row [" + i + "] has [" + (row == null ? "null" : row.length) + "]");
            }
            for (int j = 0; j < numCols; j++) {
                data[j * numRows + i] = row[j];
            }
        }
        return new FloatMatrix(data, numRows, numCols);
    }

    public float get(int row, int col) {
        checkIndex(row, col);
        return data[col * rows + row];
    }

    public void set(int row, int col, float value) {
        checkIndex(row, col);
        data[col * rows + row] = value;
    }

    /**
     * The live column-major backing array — element {@code (row, col)} lives at
     * {@code col * rows() + row}. Exposed without copying so hot loops can run
     * directly over it; treat it as read-only unless you own the matrix.
     */
    public float[] data() {
        return data;
    }

    /**
     * Copies the matrix into a row-oriented {@code float[rows][cols]} array,
     * the layout most convenient for interoperability and display.
     */
    public float[][] toRowArrays() {
        float[][] result = new float[rows][cols];
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
    public FloatMatrix copy() {
        return new FloatMatrix(Arrays.copyOf(data, data.length), rows, cols);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FloatMatrix other)) {
            return false;
        }
        return rows == other.rows && cols == other.cols && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * rows + cols) + Arrays.hashCode(data);
    }
}
