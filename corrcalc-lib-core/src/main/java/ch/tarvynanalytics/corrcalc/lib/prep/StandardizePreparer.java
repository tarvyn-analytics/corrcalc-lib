package ch.tarvynanalytics.corrcalc.lib.prep;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

/**
 * Standardizes each column to a z-score: subtracts the column mean and divides
 * by the sample standard deviation (using the {@code n-1} denominator).
 * <p>
 * Zero-variance columns (including the {@code n == 1} case where the sample
 * standard deviation is undefined) are only centered, i.e. they come out as
 * all zeros — the same convention scikit-learn's {@code StandardScaler} uses.
 */
final class StandardizePreparer implements DataPreparer {

    @Override
    public DoubleMatrix prepare(DoubleMatrix observations) {
        int n = observations.rows();
        if (n < 1) {
            throw new InvalidInputException("Standardizing requires at least one observation row");
        }
        DoubleMatrix result = observations.copy();
        int p = result.cols();
        double[] data = result.data();

        for (int j = 0; j < p; j++) {
            int offset = j * n;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += data[offset + i];
            }
            double mean = sum / n;

            double sumSq = 0;
            for (int i = 0; i < n; i++) {
                double centered = data[offset + i] - mean;
                data[offset + i] = centered;
                sumSq += centered * centered;
            }
            if (sumSq == 0 || n == 1) {
                continue;
            }
            // use (n-1) for the sample standard deviation
            double invStdDev = 1.0 / Math.sqrt(sumSq / (n - 1));
            for (int i = 0; i < n; i++) {
                data[offset + i] *= invStdDev;
            }
        }
        return result;
    }
}
