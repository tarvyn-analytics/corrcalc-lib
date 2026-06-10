package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;

/**
 * Factory for accessing the available correlation calculators.
 * <p>
 * All calculators are stateless and thread-safe, so a single shared instance
 * is returned for each type.
 */
public final class Correlations {

    private static final CorrelationCalculator PEARSON = new PearsonCorrelationCalculator();

    // utility class/factory
    private Correlations() {
        // no instance
    }

    /**
     * Returns a calculator for the Pearson correlation matrix.
     */
    public static CorrelationCalculator pearson() {
        return PEARSON;
    }

    /**
     * Returns the calculator for the given correlation type.
     */
    public static CorrelationCalculator of(CorrelationType type) {
        if (type == null) {
            throw new InvalidInputException("Correlation type must not be null");
        }
        return switch (type) {
            case PEARSON -> PEARSON;
        };
    }
}
