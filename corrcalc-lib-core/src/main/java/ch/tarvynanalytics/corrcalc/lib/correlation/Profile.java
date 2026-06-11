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
    STANDARD
}
