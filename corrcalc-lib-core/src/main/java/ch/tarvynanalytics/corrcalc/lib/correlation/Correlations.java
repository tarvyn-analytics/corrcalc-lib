package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for accessing the available correlation calculators.
 * <p>
 * All calculators are stateless and thread-safe, so a single shared instance
 * is returned per correlation type and {@link Profile}. Calculators are
 * created lazily: a profile whose JVM requirements are not met (a future
 * vectorized profile, say) must only fail when that profile is requested,
 * never on class initialization.
 */
public final class Correlations {

    private static final ConcurrentMap<Profile, CorrelationCalculator> PEARSON_BY_PROFILE =
            new ConcurrentHashMap<>();

    // utility class/factory
    private Correlations() {
        // no instance
    }

    /**
     * Returns a calculator for the Pearson correlation matrix using the
     * {@link Profile#STANDARD} profile.
     */
    public static CorrelationCalculator pearson() {
        return pearson(Profile.STANDARD);
    }

    /**
     * Returns a calculator for the Pearson correlation matrix using the given
     * calculation profile.
     */
    public static CorrelationCalculator pearson(Profile profile) {
        if (profile == null) {
            throw new InvalidInputException("Calculation profile must not be null");
        }
        return PEARSON_BY_PROFILE.computeIfAbsent(profile, Correlations::newPearson);
    }

    /**
     * Returns the calculator for the given correlation type using the
     * {@link Profile#STANDARD} profile.
     */
    public static CorrelationCalculator of(CorrelationType type) {
        return of(type, Profile.STANDARD);
    }

    /**
     * Returns the calculator for the given correlation type and calculation
     * profile.
     */
    public static CorrelationCalculator of(CorrelationType type, Profile profile) {
        if (type == null) {
            throw new InvalidInputException("Correlation type must not be null");
        }
        return switch (type) {
            case PEARSON -> pearson(profile);
        };
    }

    private static CorrelationCalculator newPearson(Profile profile) {
        return switch (profile) {
            case STANDARD -> new PearsonCorrelationCalculator(new DoubleKernels(), new FloatKernels());
        };
    }
}
