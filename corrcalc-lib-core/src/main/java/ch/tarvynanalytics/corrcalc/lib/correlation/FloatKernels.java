package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * The {@link Kernels} implementation for single-precision storage. Sums still
 * accumulate in double; only the stored elements are floats.
 */
class FloatKernels implements Kernels<float[]> {

    @Override
    public float[] allocate(int length) {
        return new float[length];
    }

    @Override
    public void normalizeColumn(float[] src, float[] dst, int n, int col) {
        int offset = col * n;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += src[offset + i];
        }
        double mean = sum / n;

        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double centered = src[offset + i] - mean;
            dst[offset + i] = (float) centered;
            sumSq += centered * centered;
        }

        if (sumSq == 0) {
            // zero variance: the correlation coefficient is undefined
            for (int i = 0; i < n; i++) {
                dst[offset + i] = Float.NaN;
            }
        } else {
            double invNorm = 1.0 / Math.sqrt(sumSq);
            for (int i = 0; i < n; i++) {
                dst[offset + i] = (float) (dst[offset + i] * invNorm);
            }
        }
    }

    @Override
    public double dot(float[] data, int offsetI, int offsetJ, int n) {
        // deliberately a single accumulator: the 4-accumulator unroll that
        // speeds up DoubleKernels.dot measured 15-19% SLOWER here on the
        // reference machine (COR-319) — the float->double widening reacts
        // badly to the unrolled shape. Re-measure before changing this loop.
        double r = 0;
        for (int row = 0; row < n; row++) {
            r += (double) data[offsetI + row] * data[offsetJ + row];
        }
        return r;
    }

    @Override
    public void set(float[] data, int index, double value) {
        data[index] = (float) value;
    }
}
