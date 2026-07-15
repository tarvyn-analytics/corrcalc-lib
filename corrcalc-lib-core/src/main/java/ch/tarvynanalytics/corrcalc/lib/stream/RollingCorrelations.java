package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;

/**
 * Factory for {@link RollingCorrelationEngine} instances, mirroring
 * {@link ch.tarvynanalytics.corrcalc.lib.correlation.Correlations} for the
 * batch calculators.
 * <p>
 * Unlike the batch calculators, a rolling engine is <b>stateful</b> (see
 * {@link RollingCorrelationEngine}), so this factory hands out a fresh
 * instance per call rather than a shared per-profile singleton — there is
 * exactly one engine per stream (one symbol basket, one sampling frequency),
 * and sharing one across streams would mix their windows.
 * <p>
 * <b>Profile gating in v1:</b> every {@link Profile} accumulates the running
 * sums in {@code double} and computes the same estimator
 * (see the numerics spec, "the one estimator"). {@link Profile#STANDARD}'s
 * scalar rank-one slide and snapshot assembly is the only implementation
 * that exists today; {@link Profile#HIGH_PERFORMANCE} and
 * {@link Profile#VECTORIZED} currently resolve to the same engine — an
 * explicit, documented stub (see {@link StandardRollingPearsonEngine}), not
 * silent identity, because the per-snapshot cost ({@code O(N^2)}) is tiny
 * next to the batch calculators' {@code O(n*p^2)} that those profiles
 * actually optimize. SIMD/tiling over pairs is a fast-follow once a snapshot
 * size makes it worth it.
 */
public final class RollingCorrelations {

    // utility class/factory
    private RollingCorrelations() {
        // no instance
    }

    /**
     * Creates a rolling Pearson engine using the {@link Profile#STANDARD} profile,
     * emitting a snapshot every bar once the window has filled.
     *
     * @param labels   the variable labels, in the column order every {@code onBar}
     *                 call must use; also handed back on every {@code onSnapshot}
     * @param window   the rolling window width in bars, {@code >= 2}
     * @param listener receives the matrix snapshots
     */
    public static RollingCorrelationEngine pearson(
            String[] labels, int window, CorrelationStreamListener listener) {
        return pearson(labels, window, 1, listener, Profile.STANDARD);
    }

    /**
     * Creates a rolling Pearson engine using the given calculation profile and
     * emit cadence.
     *
     * @param labels            the variable labels, in the column order every
     *                          {@code onBar} call must use
     * @param window            the rolling window width in bars, {@code >= 2}
     * @param emitEveryNUpdates emit a snapshot once every this many bars once
     *                          the window has filled, {@code >= 1}
     * @param listener          receives the matrix snapshots
     * @param profile           selects the engine implementation (see the class javadoc
     *                          for what differs, and does not yet differ, per profile)
     */
    public static RollingCorrelationEngine pearson(
            String[] labels, int window, int emitEveryNUpdates,
            CorrelationStreamListener listener, Profile profile) {
        if (profile == null) {
            throw new InvalidInputException("Calculation profile must not be null");
        }
        return switch (profile) {
            case STANDARD, HIGH_PERFORMANCE, VECTORIZED ->
                    new StandardRollingPearsonEngine(labels, window, emitEveryNUpdates, listener);
        };
    }
}
