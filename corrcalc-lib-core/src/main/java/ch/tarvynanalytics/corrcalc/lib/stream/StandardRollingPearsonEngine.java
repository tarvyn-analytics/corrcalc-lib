package ch.tarvynanalytics.corrcalc.lib.stream;

/**
 * The portable baseline rolling-Pearson engine: the scalar rank-one slide
 * and snapshot assembly from {@link AbstractRollingPearsonEngine}, with no
 * profile-specific changes. Backs {@link ch.tarvynanalytics.corrcalc.lib.correlation.Profile#STANDARD}.
 * <p>
 * <b>Also backs {@link ch.tarvynanalytics.corrcalc.lib.correlation.Profile#HIGH_PERFORMANCE}
 * and {@link ch.tarvynanalytics.corrcalc.lib.correlation.Profile#VECTORIZED} in v1</b> — see
 * {@link RollingCorrelations} for why: the per-snapshot {@code cov/sqrt(va*vb)}
 * work is {@code O(N^2)}, tiny next to the batch calculators' {@code O(n*p^2)},
 * so register-blocked/SIMD kernels are not worth specializing yet. This is an
 * explicit, documented stub, not a silent identity — a future PR may give
 * those profiles their own tiled/vectorized snapshot assembly without
 * changing this class or the public API.
 */
final class StandardRollingPearsonEngine extends AbstractRollingPearsonEngine {

    StandardRollingPearsonEngine(String[] labels, int window, int emitEveryNUpdates,
                                  CorrelationStreamListener listener) {
        super(labels, window, emitEveryNUpdates, listener);
    }
}
