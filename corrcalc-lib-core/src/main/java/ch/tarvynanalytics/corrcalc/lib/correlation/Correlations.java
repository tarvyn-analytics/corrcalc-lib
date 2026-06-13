package ch.tarvynanalytics.corrcalc.lib.correlation;

import ch.tarvynanalytics.corrcalc.lib.exception.CorrCalcException;
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
            case STANDARD -> {
                FloatKernels floatKernels = new FloatKernels();
                yield new PearsonCorrelationCalculator(new DoubleKernels(), floatKernels, floatKernels);
            }
            case HIGH_PERFORMANCE -> {
                TiledFloatKernels floatKernels = new TiledFloatKernels();
                yield new PearsonCorrelationCalculator(new TiledDoubleKernels(), floatKernels, floatKernels);
            }
            case VECTORIZED -> newVectorizedPearson();
        };
    }

    /**
     * The vectorized kernels are class-file version 69 and link against the
     * incubator Vector API, so they are loaded reflectively: a JVM that never
     * requests {@link Profile#VECTORIZED} never links them, and one that does
     * without meeting the requirements gets a clear failure instead of a
     * {@code NoClassDefFoundError} from an arbitrary call site.
     */
    @SuppressWarnings("unchecked")
    private static CorrelationCalculator newVectorizedPearson() {
        String pkg = Correlations.class.getPackageName();
        try {
            Kernels<double[]> doubleKernels = (Kernels<double[]>)
                    Class.forName(pkg + ".VectorizedDoubleKernels").getDeclaredConstructor().newInstance();
            Kernels<float[]> fastFloatKernels = (Kernels<float[]>)
                    Class.forName(pkg + ".VectorizedFastFloatKernels").getDeclaredConstructor().newInstance();
            Kernels<float[]> preciseFloatKernels = (Kernels<float[]>)
                    Class.forName(pkg + ".VectorizedFloatKernels").getDeclaredConstructor().newInstance();
            return new PearsonCorrelationCalculator(doubleKernels, fastFloatKernels, preciseFloatKernels);
        } catch (ReflectiveOperationException | NoClassDefFoundError | UnsupportedClassVersionError e) {
            throw new CorrCalcException(
                    "Profile.VECTORIZED requires a Java 25+ JVM started with"
                            + " --add-modules jdk.incubator.vector", e);
        }
    }
}
