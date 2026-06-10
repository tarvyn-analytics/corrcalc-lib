package ch.corrcalc.lib.prep;

import ch.corrcalc.lib.matrix.DoubleMatrix;

/**
 * A data preparation step transforming an observations matrix into a new one,
 * e.g. removing missing values or standardizing columns.
 * <p>
 * Implementations never mutate the input matrix and are stateless, so they can
 * be reused and chained freely via {@link #andThen(DataPreparer)}.
 */
@FunctionalInterface
public interface DataPreparer {

    /**
     * @param observations an {@code n x p} matrix, one observation per row
     * @return the prepared matrix; always a new instance
     */
    DoubleMatrix prepare(DoubleMatrix observations);

    /**
     * Returns a preparer applying this step first and {@code next} on its result.
     */
    default DataPreparer andThen(DataPreparer next) {
        return observations -> next.prepare(this.prepare(observations));
    }
}
