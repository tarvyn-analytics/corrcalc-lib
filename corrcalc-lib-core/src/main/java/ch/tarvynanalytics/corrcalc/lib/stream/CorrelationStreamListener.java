package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

import java.time.Instant;

/**
 * Receives the output of a {@link RollingCorrelationEngine}.
 * <p>
 * <b>Snapshot-now / delta-ready.</b> {@link #onSnapshot} is the v1 contract:
 * the full {@code N x N} Pearson matrix, emitted whenever the engine's emit
 * cadence is due. {@link #onDelta} is declared but <b>not emitted in v1</b> —
 * it exists purely so a future, sparse-update delta channel can be added
 * without an API break for listeners that only implement {@code onSnapshot}.
 * <p>
 * A rank-one slide of one bar changes, for every variable {@code i} whose
 * return moved, the cell {@code (i, j)} for <b>every other</b> {@code j}
 * (because {@code Sx_i}/{@code Sxx_i} and every {@code Sxy_ij} touching
 * {@code i} changed). A full-vector bar — the only kind v1 ingests — there­
 * fore touches the whole matrix, which is exactly why a delta channel earns
 * nothing yet: it only pays off for a future, genuinely sparse bar update.
 */
public interface CorrelationStreamListener {

    /**
     * Called with the current rolling Pearson matrix whenever the engine's
     * emit cadence is due.
     *
     * @param seq    monotonic counter starting at 0, incremented once per
     *               <em>emission</em> (not once per bar when the cadence is
     *               greater than 1)
     * @param asOf   the timestamp of the newest bar in the window (the bar
     *               that triggered this emission)
     * @param pearson the current {@code N x N} Pearson correlation matrix;
     *               off-diagonal cells are {@code NaN} where either variable
     *               has non-positive variance over the window, and so is the
     *               diagonal cell of a zero-variance variable (see
     *               {@link RollingCorrelations} for the full NaN contract)
     * @param labels the variable labels, same array reference and column
     *               order as passed to {@link RollingCorrelations#pearson}
     */
    void onSnapshot(long seq, Instant asOf, DoubleMatrix pearson, String[] labels);

    /**
     * Reserved for a future sparse-delta channel; never called in v1. The
     * default implementation is a no-op so existing listeners are unaffected
     * when this is eventually wired up.
     *
     * @param seq   the same monotonic emission counter as {@link #onSnapshot}
     * @param delta the changed cells of a rank-one slide (whole-matrix for a
     *              full-vector bar update, see the class javadoc)
     */
    default void onDelta(long seq, CorrelationDelta delta) {
        // reserved no-op in v1 - see class javadoc
    }
}
