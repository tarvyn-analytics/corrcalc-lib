package ch.tarvynanalytics.corrcalc.lib.prep;

import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

/**
 * Removes every observation row that contains at least one NaN value
 * (listwise deletion). May produce a matrix with zero rows when every
 * row has a missing value.
 */
final class DropMissingRowsPreparer implements DataPreparer {

    @Override
    public DoubleMatrix prepare(DoubleMatrix observations) {
        int n = observations.rows();
        int p = observations.cols();
        double[] src = observations.data();

        boolean[] keep = new boolean[n];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            keep[i] = true;
            for (int j = 0; j < p; j++) {
                if (Double.isNaN(src[j * n + i])) {
                    keep[i] = false;
                    break;
                }
            }
            if (keep[i]) {
                kept++;
            }
        }
        if (kept == n) {
            return observations.copy();
        }

        double[] result = new double[Math.multiplyExact(kept, p)];
        for (int j = 0; j < p; j++) {
            int srcOffset = j * n;
            int dst = j * kept;
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    result[dst++] = src[srcOffset + i];
                }
            }
        }
        return DoubleMatrix.columnMajor(result, kept, p);
    }
}
