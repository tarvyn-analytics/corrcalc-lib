package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * The {@link Kernels} implementation for double-precision storage.
 */
final class DoubleKernels implements Kernels<double[]> {

    @Override
    public double[] allocate(int length) {
        return new double[length];
    }

    @Override
    public void normalizeColumn(double[] src, double[] dst, int n, int col) {
        int offset = col * n;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += src[offset + i];
        }
        double mean = sum / n;

        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double centered = src[offset + i] - mean;
            dst[offset + i] = centered;
            sumSq += centered * centered;
        }

        if (sumSq == 0) {
            // zero variance: the correlation coefficient is undefined
            for (int i = 0; i < n; i++) {
                dst[offset + i] = Double.NaN;
            }
        } else {
            double invNorm = 1.0 / Math.sqrt(sumSq);
            for (int i = 0; i < n; i++) {
                dst[offset + i] *= invNorm;
            }
        }
    }

    @Override
    public double dot(double[] data, int offsetI, int offsetJ, int n) {
        double r = 0;
        for (int row = 0; row < n; row++) {
            r += data[offsetI + row] * data[offsetJ + row];
        }
        return r;
    }

    @Override
    public void set(double[] data, int index, double value) {
        data[index] = value;
    }
}
