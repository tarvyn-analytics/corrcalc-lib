package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * The element-type-specific inner loops shared by the correlation calculators,
 * so that the calculation engines can be written once, generic over the flat
 * column-major storage array {@code A} ({@code double[]} or {@code float[]}).
 * <p>
 * All means, sums of squares and dot products accumulate in double regardless
 * of the storage type; only loads and stores touch the element type. See
 * {@link DoubleKernels} and {@link FloatKernels} for the two implementations.
 */
interface Kernels<A> {

    A allocate(int length);

    /**
     * Centers column {@code col} of {@code src} and scales it by the inverse of
     * its centered norm, writing into the same column of {@code dst}. A
     * zero-variance column is filled with NaN so each pairwise dot product
     * against it propagates NaN naturally.
     */
    void normalizeColumn(A src, A dst, int n, int col);

    /**
     * Dot product of the {@code n} elements at {@code offsetI} and {@code offsetJ}.
     */
    double dot(A data, int offsetI, int offsetJ, int n);

    /**
     * Side length of the column tiles the engine should feed to
     * {@link #dotTile}. {@code 1} means plain pairwise dot products.
     */
    default int tileSize() {
        return 1;
    }

    /**
     * Dot products between every column in {@code [colI0, colI0+countI)} and
     * every column in {@code [colJ0, colJ0+countJ)}, written to
     * {@code dots[ii * countJ + jj]}. Tiled implementations compute the whole
     * block in one pass over the rows so each loaded value is reused
     * {@code countI}/{@code countJ} times instead of streaming both columns
     * per pair.
     */
    default void dotTile(A data, int n, int colI0, int countI, int colJ0, int countJ, double[] dots) {
        for (int ii = 0; ii < countI; ii++) {
            for (int jj = 0; jj < countJ; jj++) {
                dots[ii * countJ + jj] = dot(data, (colI0 + ii) * n, (colJ0 + jj) * n, n);
            }
        }
    }
}
