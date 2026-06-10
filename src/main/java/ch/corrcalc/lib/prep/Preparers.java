package ch.corrcalc.lib.prep;

import ch.corrcalc.lib.exception.InvalidInputException;

/**
 * Factory for accessing the available data preparation steps.
 * <p>
 * All preparers are stateless and thread-safe, so a single shared instance
 * is returned for each kind. Steps compose into pipelines either with
 * {@link DataPreparer#andThen(DataPreparer)} or {@link #pipeline(DataPreparer...)}:
 * <pre>{@code
 * DataPreparer cleanAndScale = Preparers.pipeline(Preparers.imputeMean(), Preparers.standardize());
 * Matrix prepared = cleanAndScale.prepare(raw);
 * }</pre>
 */
public final class Preparers {

    private static final DataPreparer DROP_MISSING_ROWS = new DropMissingRowsPreparer();
    private static final DataPreparer IMPUTE_MEAN = new MeanImputePreparer();
    private static final DataPreparer CENTER = new CenterPreparer();
    private static final DataPreparer STANDARDIZE = new StandardizePreparer();

    // utility class/factory
    private Preparers() {
        // no instance
    }

    /**
     * Returns a preparer that removes every row containing at least one NaN value.
     */
    public static DataPreparer dropMissingRows() {
        return DROP_MISSING_ROWS;
    }

    /**
     * Returns a preparer that replaces NaN values with the mean of the non-missing
     * values in the same column.
     */
    public static DataPreparer imputeMean() {
        return IMPUTE_MEAN;
    }

    /**
     * Returns a preparer that subtracts each column's mean so all columns are
     * centered on zero.
     */
    public static DataPreparer center() {
        return CENTER;
    }

    /**
     * Returns a preparer that centers each column and divides it by its sample
     * standard deviation (z-score). Zero-variance columns are only centered,
     * i.e. they come out as all zeros.
     */
    public static DataPreparer standardize() {
        return STANDARDIZE;
    }

    /**
     * Composes the given steps into one preparer applying them in order.
     */
    public static DataPreparer pipeline(DataPreparer... steps) {
        if (steps == null || steps.length == 0) {
            throw new InvalidInputException("A pipeline requires at least one preparation step");
        }
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == null) {
                throw new InvalidInputException("Pipeline step [" + i + "] must not be null");
            }
        }
        DataPreparer composed = steps[0];
        for (int i = 1; i < steps.length; i++) {
            composed = composed.andThen(steps[i]);
        }
        return composed;
    }
}
