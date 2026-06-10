package ch.corrcalc.lib;

import ch.corrcalc.lib.correlation.Correlations;
import ch.corrcalc.lib.io.CsvMatrixReader;
import ch.corrcalc.lib.matrix.Matrix;
import ch.corrcalc.lib.prep.DataPreparer;
import ch.corrcalc.lib.prep.Preparers;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the full library flow: parse a whitespace-separated file with
 * missing values, prepare the data, and calculate the Pearson correlation matrix.
 */
class CorrelationEndToEndTest {

    @Test
    void csvWithMissingValues_ImputedAndCorrelated_ProducesExpectedMatrix() throws IOException {
        String file = """
                1 2 1.0
                2 4 NaN
                3 6 3.5
                4 8 2.0
                """;
        Matrix raw = new CsvMatrixReader()
                .read(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)), 4, 3);

        DataPreparer preparation = Preparers.pipeline(Preparers.imputeMean(), Preparers.standardize());
        Matrix corr = Correlations.pearson().calculate(preparation.prepare(raw));

        assertEquals(3, corr.rows());
        assertEquals(3, corr.cols());
        // columns 0 and 1 are perfectly correlated (y = 2x)
        assertEquals(1.0, corr.get(0, 1), 1e-12);
        // diagonal is always exactly one
        for (int i = 0; i < 3; i++) {
            assertEquals(1.0, corr.get(i, i), 0.0);
        }
        // column 2 after imputation is {1.0, 2.1666..., 3.5, 2.0}; the coefficient against
        // column 0 was cross-checked with the naive textbook formula
        double expected = pearsonOf(new double[]{1, 2, 3, 4}, new double[]{1.0, 13.0 / 6.0, 3.5, 2.0});
        assertEquals(expected, corr.get(0, 2), 1e-12);
        assertEquals(expected, corr.get(2, 0), 1e-12);
    }

    @Test
    void dropMissingRowsFlow_RemovesIncompleteObservationsBeforeCorrelating() throws IOException {
        String file = """
                1 -1
                2 NaN
                3 -3
                5 -5
                """;
        Matrix raw = new CsvMatrixReader()
                .read(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)), 4, 2);

        Matrix corr = Correlations.pearson().calculate(Preparers.dropMissingRows().prepare(raw));

        assertEquals(-1.0, corr.get(0, 1), 1e-12);
    }

    private static double pearsonOf(double[] x, double[] y) {
        int n = x.length;
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;
        double cov = 0;
        double varX = 0;
        double varY = 0;
        for (int i = 0; i < n; i++) {
            cov += (x[i] - meanX) * (y[i] - meanY);
            varX += (x[i] - meanX) * (x[i] - meanX);
            varY += (y[i] - meanY) * (y[i] - meanY);
        }
        return cov / Math.sqrt(varX * varY);
    }
}
