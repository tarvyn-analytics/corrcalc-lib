package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;

import java.time.Instant;

/**
 * The changed cells of a rolling correlation matrix between two consecutive
 * emissions, for the future sparse-delta channel ({@link CorrelationStreamListener#onDelta}).
 * <p>
 * <b>Reserved — not emitted in v1.</b> This type exists only so the
 * {@code onDelta} signature is stable once a delta channel is implemented.
 * Its intended semantics: for a rank-one window slide of a single full-vector
 * bar, every variable's running sums change, so the changed-cell set is the
 * <b>whole matrix</b> — a future delta channel only earns its keep once the
 * engine accepts genuinely sparse bar updates (a subset of variables per
 * call), which v1 does not. {@code changed} carries the full {@code N x N}
 * matrix of changed coefficients (same NaN contract as
 * {@link CorrelationStreamListener#onSnapshot}) until a sparser representation
 * is needed.
 *
 * @param changed the changed coefficients, {@code N x N}, same shape and NaN
 *                contract as the snapshot matrix
 * @param asOf    the timestamp of the bar that produced this delta
 */
public record CorrelationDelta(DoubleMatrix changed, Instant asOf) {
}
