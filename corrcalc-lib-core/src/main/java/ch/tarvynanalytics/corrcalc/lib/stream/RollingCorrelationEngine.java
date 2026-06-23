package ch.tarvynanalytics.corrcalc.lib.stream;

import java.time.Instant;

/**
 * A streaming engine that maintains a rolling-window Pearson correlation
 * matrix incrementally, one bar at a time, and emits snapshots to a
 * {@link CorrelationStreamListener} on a cadence.
 * <p>
 * <b>This is a deliberate, documented exception to the calculators' stateless
 * invariant.</b> Every {@code CorrelationCalculator} in this library is
 * stateless and thread-safe; a {@code RollingCorrelationEngine} is the
 * opposite by necessity — it carries the running sums of the current window
 * forward from one {@link #onBar} call to the next. An instance is therefore
 * <b>single-writer: exactly one ingest thread may call {@link #onBar} /
 * {@link #onSessionBoundary}</b>, never concurrently. Construct one instance
 * per stream (one symbol basket, one sampling frequency) via
 * {@link RollingCorrelations}.
 * <p>
 * <b>Calendar-agnostic.</b> The engine has no notion of trading sessions or
 * calendars; it only knows "the caller said reset here". Callers (the
 * downstream {@code ReturnBuilder}, S2) call {@link #onSessionBoundary()}
 * whenever a return would otherwise cross a session gap, which clears the
 * window so no rolling correlation is ever computed across the gap.
 * <p>
 * <b>One timescale per instance.</b> Every call to {@link #onBar} must carry
 * returns sampled at the same frequency (e.g. always daily, or always
 * 1-minute); the engine never concatenates or mixes timescales. Combining a
 * macro and a micro view is done downstream by blending the two engines'
 * matrix snapshots, never inside one engine.
 * <p>
 * <b>Input contract — feed returns, not levels.</b> Each value in a bar must be a
 * per-bar <b>return</b> (for example {@code log(close / prevClose)}), i.e. a series
 * fluctuating around approximately zero, and must be <b>finite</b> (no {@code NaN}
 * or {@code Infinity} — clean raw data with the {@code prep} package first, exactly
 * as for the batch {@code CorrelationCalculator}). The rolling variance is computed
 * from running sums as {@code Sxx − W·mean²}; that subtraction is numerically exact
 * while the mean is small relative to the spread, but loses precision as the mean
 * moves away from zero — the relative error per coefficient is on the order of
 * {@code eps · (1 + (mean/std)²)}. It is therefore accurate to ~1e-13 for returns,
 * but <b>degrades for inputs with a large mean-to-spread ratio</b>, such as raw
 * price levels or a near-constant series pinned on a large offset. This uncentered
 * form is a deliberate trade: it is what makes the per-bar update {@code O(1)} per
 * pair and keeps the result bit-for-bit consistent with the batch Pearson over the
 * same window. A variable that is <b>constant over the window</b> has zero variance,
 * so its entire row and column — including the diagonal — are reported as
 * {@code NaN}. The window must be {@code >= 2}, and every bar's length must equal
 * the variable count.
 */
public interface RollingCorrelationEngine {

    /**
     * Ingests one bar of returns, one value per variable, in the same column
     * order as the {@code labels} the engine was constructed with. Updates
     * the rolling window in O(1) per pair (a rank-one slide: the oldest bar
     * still in the window is removed, then this bar is added) and, once the
     * window has filled and the emit cadence is due, calls back the
     * configured {@link CorrelationStreamListener}.
     *
     * @param asOf    the timestamp of this bar; carried as-is to {@code onSnapshot}
     *                as the newest bar in the window when a snapshot is emitted
     * @param returns one log-return per variable, length == the engine's variable
     *                count, column order == the labels passed at construction
     */
    void onBar(Instant asOf, double[] returns);

    /**
     * Single-precision variant of {@link #onBar(Instant, double[])}. The
     * running sums still accumulate in {@code double} — only the per-bar
     * input is single precision (see the class javadoc of
     * {@link RollingCorrelations} for the accuracy rationale).
     */
    void onBar(Instant asOf, float[] returns);

    /**
     * Signals a session boundary (a calendar gap the engine itself does not
     * compute): clears every running sum and the window, as if the engine
     * had just been constructed. The engine re-warms over the next window's
     * worth of in-session bars before it emits another snapshot. Calling
     * this on a freshly constructed or already-empty engine is a harmless
     * no-op.
     */
    void onSessionBoundary();
}
