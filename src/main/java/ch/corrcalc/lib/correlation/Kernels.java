package ch.corrcalc.lib.correlation;

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

    void set(A data, int index, double value);
}
