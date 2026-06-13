package ch.tarvynanalytics.corrcalc.lib.correlation;

/**
 * The supported correlation coefficient types.
 * <p>
 * Future candidates: Spearman, Kendall.
 */
public enum CorrelationType {

    /** Pearson product-moment correlation. */
    PEARSON,

    /**
     * Partial correlation: the correlation between two variables with the linear
     * effect of all the other variables removed (the precision-matrix route over
     * the Pearson correlation matrix).
     */
    PARTIAL
}
