package ch.tarvynanalytics.corrcalc.lib.prep;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

/**
 * Replaces NaN values with the mean of the non-missing values in the same
 * column. A column consisting entirely of NaN has no mean to impute from,
 * so it is rejected.
 */
final class MeanImputePreparer implements DataPreparer {

    @Override
    public DoubleMatrix prepare(DoubleMatrix observations) {
        DoubleMatrix result = observations.copy();
        int n = result.rows();
        int p = result.cols();
        double[] data = result.data();

        for (int j = 0; j < p; j++) {
            int offset = j * n;
            double sum = 0;
            int present = 0;
            for (int i = 0; i < n; i++) {
                double value = data[offset + i];
                if (!Double.isNaN(value)) {
                    sum += value;
                    present++;
                }
            }
            if (present == n) {
                continue;
            }
            if (present == 0) {
                throw new InvalidInputException(
                        "Column [" + j + "] has no values to impute the mean from (all NaN)");
            }
            double mean = sum / present;
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(data[offset + i])) {
                    data[offset + i] = mean;
                }
            }
        }
        return result;
    }
}
