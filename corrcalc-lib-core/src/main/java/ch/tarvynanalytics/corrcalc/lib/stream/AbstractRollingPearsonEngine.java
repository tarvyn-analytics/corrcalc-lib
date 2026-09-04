package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

import java.time.Instant;
import java.util.Arrays;

/**
 * Shared bookkeeping for the rolling-window Pearson streaming engine: the
 * window buffer, the running sums {@code (Sx, Sy, Sxx, Syy, Sxy)}, the
 * rank-one slide, the emit cadence and the matrix-snapshot assembly. Profiles
 * differ only in how {@link #onBar(Instant, double[])} feeds bars in (see
 * {@link RollingCorrelations} for which profiles exist); the math here is
 * the one estimator from the numerics spec and is never duplicated per
 * profile.
 * <p>
 * <b>Running sums always accumulate in {@code double}</b> — including on the
 * float bar-ingest path — per the library's accumulate-at-least-as-precisely
 * invariant; only the per-bar input may be single precision.
 * <p>
 * Layout: per-variable sums {@code sx[i]}, {@code sxx[i]} (N of each); the
 * pairwise {@code sxy} is stored once per unordered pair {@code (i, j), i < j}
 * in a flat upper-triangular array (length {@link #pairCount(int)}), addressed
 * by a running pair counter that walks the pairs in the same {@code i < j}
 * order on every update and on snapshot assembly. The window
 * buffer is a circular {@code double[window * n]} of the last (up to)
 * {@code window} bars, column-major within each slot (variable-major), so the
 * oldest bar can be read back for the rank-one "remove" half of the slide.
 * <p>
 * <b>Non-finite entries.</b> {@code nanInWindow[i]} counts how many of variable
 * {@code i}'s current window entries are {@code NaN}/{@code Infinity}; while it is
 * positive, {@code i}'s row/column is reported {@code NaN}, exactly like the
 * zero-variance rule. Non-finite values are never folded into {@code sx}/{@code sxx}/
 * {@code sxy} (see {@link #accumulate(int, double)}), so those sums always hold only
 * finite contributions and are exact again the moment a variable's spell drains out
 * of the window.
 */
abstract class AbstractRollingPearsonEngine implements RollingCorrelationEngine {

    private final int n;
    private final int window;
    private final String[] labels;
    private final int emitEveryNUpdates;
    private final CorrelationStreamListener listener;

    private final double[] history; // circular buffer: slot * n + variable
    private final double[] sx;
    private final double[] sxx;
    private final double[] sxy; // flat upper-triangular, index via pairIndex
    private final int[] nanInWindow; // per-variable count of non-finite entries currently in the window

    private int count; // bars accumulated since the last reset, capped at window
    private int writeSlot; // next circular-buffer slot to write
    private int updatesSinceEmit;
    private long seq;

    AbstractRollingPearsonEngine(String[] labels, int window, int emitEveryNUpdates,
                                 CorrelationStreamListener listener) {
        if (labels == null) {
            throw new InvalidInputException("Labels must not be null");
        }
        if (labels.length < 1) {
            throw new InvalidInputException("At least one variable is required, got [" + labels.length + "]");
        }
        if (window < 2) {
            throw new InvalidInputException("Window must be at least [2], got [" + window + "]");
        }
        if (emitEveryNUpdates < 1) {
            throw new InvalidInputException(
                    "Emit cadence must be at least [1], got [" + emitEveryNUpdates + "]");
        }
        if (listener == null) {
            throw new InvalidInputException("Listener must not be null");
        }
        this.n = labels.length;
        this.window = window;
        this.labels = labels;
        this.emitEveryNUpdates = emitEveryNUpdates;
        this.listener = listener;
        this.history = new double[window * n];
        this.sx = new double[n];
        this.sxx = new double[n];
        this.sxy = new double[pairCount(n)];
        this.nanInWindow = new int[n];
    }

    @Override
    public final void onBar(Instant asOf, double[] returns) {
        slide(asOf, returns);
    }

    @Override
    public final void onBar(Instant asOf, float[] returns) {
        if (returns == null) {
            throw new InvalidInputException("Returns must not be null");
        }
        double[] widened = new double[returns.length];
        for (int i = 0; i < returns.length; i++) {
            widened[i] = returns[i];
        }
        slide(asOf, widened);
    }

    @Override
    public final void onSessionBoundary() {
        Arrays.fill(history, 0.0);
        Arrays.fill(sx, 0.0);
        Arrays.fill(sxx, 0.0);
        Arrays.fill(sxy, 0.0);
        Arrays.fill(nanInWindow, 0);
        count = 0;
        writeSlot = 0;
        updatesSinceEmit = 0;
        // seq is NOT reset: it is a monotonic counter over the lifetime of
        // the engine instance, per emission, regardless of session resets.
    }

    /**
     * Rank-one window slide (remove-oldest then add-newest, per the numerics
     * spec) followed by an emission if the window is full and the cadence is
     * due.
     */
    private void slide(Instant asOf, double[] returns) {
        if (returns == null) {
            throw new InvalidInputException("Returns must not be null");
        }
        if (returns.length != n) {
            throw new InvalidInputException(
                    "Expected [" + n + "] returns (one per variable), got [" + returns.length + "]");
        }
        if (count == window) {
            removeOldest();
        }
        addNewest(returns);
        count = Math.min(count + 1, window);
        updatesSinceEmit++;

        if (count == window && updatesSinceEmit >= emitEveryNUpdates) {
            updatesSinceEmit = 0;
            emit(asOf);
        }
    }

    private void removeOldest() {
        accumulate(writeSlot * n, -1.0);
    }

    private void addNewest(double[] returns) {
        int base = writeSlot * n;
        System.arraycopy(returns, 0, history, base, n);
        accumulate(base, 1.0);
        writeSlot = (writeSlot + 1) % window;
    }

    /**
     * Folds (sign {@code +1.0}) or unfolds (sign {@code -1.0}) the window slot
     * at {@code base} into the running sums; add and remove are mirror images
     * of each other by construction, since {@code sx[i] += sign * xi} is
     * bit-identical to {@code sx[i] -= xi} for {@code sign == -1.0} (negation
     * is exact). A non-finite {@code xi} is never folded into {@code sx}/
     * {@code sxx}, only counted in {@link #nanInWindow}; a pair sum only sees
     * {@code xi * xj} when both variables are finite at this slot. Skipping is
     * exact — the running sums are only ever exposed for a variable/pair while
     * every entry that fed them was finite.
     */
    private void accumulate(int base, double sign) {
        for (int i = 0; i < n; i++) {
            double xi = history[base + i];
            if (Double.isFinite(xi)) {
                sx[i] += sign * xi;
                sxx[i] += sign * xi * xi;
            } else {
                nanInWindow[i] += sign > 0 ? 1 : -1;
            }
        }
        int idx = 0;
        for (int i = 0; i < n; i++) {
            double xi = history[base + i];
            boolean xiFinite = Double.isFinite(xi);
            for (int j = i + 1; j < n; j++) {
                if (xiFinite && Double.isFinite(history[base + j])) {
                    sxy[idx] += sign * xi * history[base + j];
                }
                idx++;
            }
        }
    }

    /** Assembles the current snapshot matrix and notifies the listener. */
    private void emit(Instant asOf) {
        double[] matrix = new double[n * n];
        double w = window;
        double[] va = new double[n];
        for (int i = 0; i < n; i++) {
            double mx = sx[i] / w;
            va[i] = nanInWindow[i] > 0 ? Double.NaN : sxx[i] - w * mx * mx;
        }
        for (int i = 0; i < n; i++) {
            matrix[i * n + i] = va[i] > 0 ? 1.0 : Double.NaN;
        }
        int idx = 0;
        for (int i = 0; i < n; i++) {
            double mxi = sx[i] / w;
            for (int j = i + 1; j < n; j++) {
                double r;
                if (!(va[i] > 0) || !(va[j] > 0)) {
                    r = Double.NaN;
                } else {
                    double mxj = sx[j] / w;
                    double cov = sxy[idx] - w * mxi * mxj;
                    r = cov / Math.sqrt(va[i] * va[j]);
                }
                matrix[j * n + i] = r;
                matrix[i * n + j] = r;
                idx++;
            }
        }
        listener.onSnapshot(seq, asOf, DoubleMatrix.columnMajor(matrix, n, n), labels);
        seq++;
    }

    /** Number of unordered pairs {@code (i, j), i < j} over {@code n} variables. */
    private static int pairCount(int n) {
        return n * (n - 1) / 2;
    }
}
