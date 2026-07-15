package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * Replaces a column's values with their ranks, using the average ("fractional")
 * rank for ties — the transform that turns a Pearson correlation into a Spearman
 * rank correlation. Ranks are 1-based; a run of {@code g} equal values that would
 * occupy positions {@code r..r+g-1} all receive their average rank
 * {@code r + (g-1)/2}.
 * <p>
 * Sorting is a stable merge sort over an {@code int} index array (no boxing), so
 * only two small scratch arrays are allocated per column. The element type is
 * read and written in place; double ranks are exact, and float ranks are exact
 * for any realistic row count (integers and halves up to {@code 2^24}).
 */
final class RankTransform {

    @FunctionalInterface
    interface IndexComparator {
        int compare(int a, int b);
    }

    private RankTransform() {
        // no instance
    }

    /** Writes the average ranks of {@code src[offset..offset+n)} into {@code dst}. */
    static void rankColumn(double[] src, double[] dst, int offset, int n) {
        int[] order = sortedIndices(n, (a, b) -> Double.compare(src[offset + a], src[offset + b]));
        int i = 0;
        while (i < n) {
            double value = src[offset + order[i]];
            int j = i;
            while (j + 1 < n && src[offset + order[j + 1]] == value) {
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
        int[] order = sortedIndices(n, (a, b) -> Float.compare(src[offset + a], src[offset + b]));
        int i = 0;
        while (i < n) {
            float value = src[offset + order[i]];
            int j = i;
            while (j + 1 < n && src[offset + order[j + 1]] == value) {
                j++;
            }
            float averageRank = (float) ((i + j + 2) / 2.0);
            for (int k = i; k <= j; k++) {
                dst[offset + order[k]] = averageRank;
            }
            i = j + 1;
        }
    }

    private static int[] sortedIndices(int n, IndexComparator comparator) {
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        if (n > 1) {
            mergeSort(indices, new int[n], 0, n, comparator);
        }
        return indices;
    }

    private static void mergeSort(int[] a, int[] tmp, int lo, int hi, IndexComparator comparator) {
        if (hi - lo < 2) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, lo, mid, comparator);
        mergeSort(a, tmp, mid, hi, comparator);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            if (comparator.compare(a[i], a[j]) <= 0) { // <= keeps the merge stable
                tmp[k++] = a[i++];
            } else {
                tmp[k++] = a[j++];
            }
        }
        while (i < mid) {
            tmp[k++] = a[i++];
        }
        while (j < hi) {
            tmp[k++] = a[j++];
        }
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }
}
