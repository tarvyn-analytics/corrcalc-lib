package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * Selects the implementation strategy of the calculators handed out by
 * {@link Correlations}. Every profile computes the same statistic and passes
 * the same correctness test suite; profiles differ only in how the inner
 * loops execute and in what they require from the JVM.
 * <p>
 * Constants are added as their implementations land, so every constant that
 * exists is fully functional. {@link #STANDARD} is what the profile-less
 * factory methods use.
 */
public enum Profile {

    /**
     * The portable baseline: straightforward scalar loops, parallelized
     * across columns, with every sum accumulating in double — also on the
     * single-precision path. No special JVM requirements.
     */
    STANDARD,

    /**
     * 4x4 register-blocked tiles: each loaded value is reused four times,
     * cutting memory traffic ~4x on bandwidth-bound inputs. Accumulation
     * stays in double, the memory budget is unchanged, and no special JVM
     * requirements apply; results differ from {@link #STANDARD} only by
     * floating-point summation order (last-ulp).
     */
    HIGH_PERFORMANCE,

    /**
     * The tiled kernels with explicit SIMD and FMA via the incubator Vector
     * API. Accumulation stays in double (float lanes are widened), memory
     * budget unchanged, results differ only by summation order. <b>Requires a
     * Java 25+ JVM started with {@code --add-modules jdk.incubator.vector}</b>;
     * requesting this profile without that fails fast with a
     * {@link ch.tarvynanalytics.corrcalc.lib.exception.CorrCalcException} —
     * there is no silent fallback.
     */
    VECTORIZED
}
