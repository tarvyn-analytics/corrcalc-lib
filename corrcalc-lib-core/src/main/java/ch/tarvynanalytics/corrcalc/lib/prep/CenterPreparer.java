package ch.tarvynanalytics.corrcalc.lib.prep;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

/**
 * Subtracts each column's mean so that every column is centered on zero.
 */
final class CenterPreparer implements DataPreparer {

    @Override
    public DoubleMatrix prepare(DoubleMatrix observations) {
        int n = observations.rows();
        if (n < 1) {
            throw new InvalidInputException("Centering requires at least one observation row");
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
            for (int i = 0; i < n; i++) {
                data[offset + i] -= mean;
            }
        }
        return result;
    }
}
