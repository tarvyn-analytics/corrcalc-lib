package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * Replaces a column's values with their ranks, using the average ("fractional")
 * rank for ties — the transform that turns a Pearson correlation into a Spearman
 * rank correlation. Ranks are 1-based; a run of {@code g} equal values that would
 * occupy positions {@code r..r+g-1} all receive their average rank
 * {@code r + (g-1)/2}.
 * <p>
 * Sorting is a stable merge sort that co-moves a contiguous copy of the values
 * and their original indices, so every comparison reads the value array
 * sequentially rather than chasing scattered indices back into the source — the
 * difference between an L1-resident sort and one bound by L2 latency on long
 * columns. Only small scratch arrays are allocated per column (no boxing). The
 * element type is read and written in place; double ranks are exact, and float
 * ranks are exact for any realistic row count (integers and halves up to
 * {@code 2^24}).
 */
final class RankTransform {

    private RankTransform() {
        // no instance
    }

    /** Writes the average ranks of {@code src[offset..offset+n)} into {@code dst}. */
    static void rankColumn(double[] src, double[] dst, int offset, int n) {
        double[] values = new double[n];
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = src[offset + i];
            order[i] = i;
        }
        if (n > 1) {
            mergeSort(values, order, new double[n], new int[n], 0, n);
        }
        int i = 0;
        while (i < n) {
            double value = values[i];
            int j = i;
            while (j + 1 < n && values[j + 1] == value) {
                j++;
            }
            double averageRank = (i + j + 2) / 2.0; // 1-based positions (i+1)..(j+1)
            for (int k = i; k <= j; k++) {
                dst[offset + order[k]] = averageRank;
            }
            i = j + 1;
        }
    }

    /** Writes the average ranks of {@code src[offset..offset+n)} into {@code dst}. */
    static void rankColumn(float[] src, float[] dst, int offset, int n) {
        float[] values = new float[n];
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = src[offset + i];
            order[i] = i;
        }
        if (n > 1) {
            mergeSort(values, order, new float[n], new int[n], 0, n);
        }
        int i = 0;
        while (i < n) {
            float value = values[i];
            int j = i;
            while (j + 1 < n && values[j + 1] == value) {
                j++;
            }
            float averageRank = (float) ((i + j + 2) / 2.0);
            for (int k = i; k <= j; k++) {
                dst[offset + order[k]] = averageRank;
            }
            i = j + 1;
        }
    }

    private static void mergeSort(double[] values, int[] order, double[] valueTmp, int[] orderTmp,
                                  int lo, int hi) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(values, order, valueTmp, orderTmp, lo, mid);
        mergeSort(values, order, valueTmp, orderTmp, mid, hi);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (values[i] <= values[j]) { // <= keeps the merge stable
                valueTmp[k] = values[i];
                orderTmp[k] = order[i];
                i++;
            } else {
                valueTmp[k] = values[j];
                orderTmp[k] = order[j];
                j++;
            }
            k++;
        }
        while (i < mid) {
            valueTmp[k] = values[i];
            orderTmp[k] = order[i];
            i++;
            k++;
        }
        while (j < hi) {
            valueTmp[k] = values[j];
            orderTmp[k] = order[j];
            j++;
            k++;
        }
        System.arraycopy(valueTmp, lo, values, lo, hi - lo);
        System.arraycopy(orderTmp, lo, order, lo, hi - lo);
    }

    private static void mergeSort(float[] values, int[] order, float[] valueTmp, int[] orderTmp,
                                  int lo, int hi) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(values, order, valueTmp, orderTmp, lo, mid);
        mergeSort(values, order, valueTmp, orderTmp, mid, hi);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (values[i] <= values[j]) { // <= keeps the merge stable
                valueTmp[k] = values[i];
                orderTmp[k] = order[i];
                i++;
            } else {
                valueTmp[k] = values[j];
                orderTmp[k] = order[j];
                j++;
            }
            k++;
        }
        while (i < mid) {
            valueTmp[k] = values[i];
            orderTmp[k] = order[i];
            i++;
            k++;
        }
        while (j < hi) {
            valueTmp[k] = values[j];
            orderTmp[k] = order[j];
            j++;
            k++;
        }
        System.arraycopy(valueTmp, lo, values, lo, hi - lo);
        System.arraycopy(orderTmp, lo, order, lo, hi - lo);
    }
}
