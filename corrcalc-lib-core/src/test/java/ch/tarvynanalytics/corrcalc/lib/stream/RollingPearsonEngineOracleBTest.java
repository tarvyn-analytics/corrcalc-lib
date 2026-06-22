package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle B from the S1 numerics spec: the streaming engine must reproduce the
 * Python spike's {@code replay_crypto_vec} matrix-derived outputs <b>bar for
 * bar</b> on a shared, committed fixture — proving the Java and Python engines
 * compute the identical rolling Pearson matrix.
 * <p>
 * <b>Provenance.</b> {@code s1_parity_returns.csv} / {@code s1_parity_expected.csv}
 * are produced by {@code spike/export_s1_fixture.py} from the synthetic panel in
 * {@code replay_crypto_vec._verify()} — seed 20260622, symbols A..E (E is a
 * constant zero-variance series, exercising the NaN branch), W=90, tau=0.5. The
 * per-window {@code n_edges}/{@code density}/{@code avg_abs_corr} are computed by
 * the spike's own {@code rolling_fusedness_vec}, never by this engine.
 * <p>
 * <b>Layering (anti-scope-creep).</b> The engine emits only the correlation
 * matrix; it knows nothing of edges, density, tau or {@code avg_abs_corr}. Those
 * graph-layer statistics (S3's domain) are re-implemented <b>here in the test</b>
 * purely as a sensitive fingerprint of the whole matrix: {@code n_edges} exact
 * means every one of the N(N−1)/2 cells landed on the correct side of tau, and
 * {@code avg_abs_corr ≤ 1e-9} means every {@code |r|} matched.
 * <p>
 * <b>Tolerances are normative:</b> {@code n_edges} exact, {@code density} exact,
 * {@code avg_abs_corr ≤ 1e-9}. They must not be loosened.
 */
class RollingPearsonEngineOracleBTest {

    /** Matches the fixture provenance (export_s1_fixture.py). */
    private static final int WINDOW = 90;
    private static final double TAU = 0.5;
    private static final double AVG_ABS_CORR_TOL = 1e-9;
    private static final double DENSITY_TOL = 1e-12;
    private static final String[] LABELS = {"A", "B", "C", "D", "E"};

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_SpikeSyntheticPanel_ReproducesFusednessStatsBarForBar(Profile profile) {
        double[][] returns = loadReturns("/s1_parity_returns.csv", LABELS.length);
        List<ExpectedWindow> expected = loadExpected("/s1_parity_expected.csv");

        int nVars = LABELS.length;
        int nPairs = nVars * (nVars - 1) / 2;

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, WINDOW, 1,
                (seq, asOf, pearson, lbls) -> snapshots.add(pearson), profile);
        for (int t = 0; t < returns.length; t++) {
            engine.onBar(Instant.ofEpochSecond(t), returns[t]);
        }

        assertEquals(returns.length - WINDOW + 1, snapshots.size(),
                "snapshot count must equal the number of full windows");
        assertEquals(expected.size(), snapshots.size(),
                "engine emitted a different number of windows than the fixture expects");

        boolean sawNaNExcludedPair = false;
        for (int k = 0; k < snapshots.size(); k++) {
            // Re-implement the spike's threshold -> graph-stats reduction IN THE TEST.
            DoubleMatrix m = snapshots.get(k);
            int nEdges = 0;
            int nCounted = 0;
            double absSum = 0.0;
            for (int i = 0; i < nVars; i++) {
                for (int j = i + 1; j < nVars; j++) {
                    double r = m.get(i, j);
                    if (Double.isNaN(r)) {
                        continue; // invalid pair: not counted, not summed, not an edge
                    }
                    nCounted++;
                    double abs = Math.abs(r);
                    absSum += abs;
                    if (abs > TAU) { // strict, matching fusedness()
                        nEdges++;
                    }
                }
            }
            double density = (double) nEdges / nPairs;       // divides by ALL pairs (spike line 158)
            double avgAbsCorr = nCounted == 0 ? Double.NaN : absSum / nCounted;
            if (nCounted < nPairs) {
                sawNaNExcludedPair = true;
            }

            ExpectedWindow exp = expected.get(k);
            assertEquals(exp.nEdges, nEdges, "n_edges mismatch at window " + k);
            assertEquals(exp.density, density, DENSITY_TOL, "density mismatch at window " + k);
            if (Double.isNaN(exp.avgAbsCorr)) {
                assertTrue(Double.isNaN(avgAbsCorr), "avg_abs_corr NaN-agreement at window " + k);
            } else {
                assertEquals(exp.avgAbsCorr, avgAbsCorr, AVG_ABS_CORR_TOL,
                        "avg_abs_corr mismatch at window " + k);
            }
        }

        assertTrue(sawNaNExcludedPair,
                "the constant series E must make at least one window exclude its pairs from the count");
    }

    // --- fixture loading (zero-dep: plain split parse of the committed CSVs) ---

    private static double[][] loadReturns(String resource, int nVars) {
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader reader = open(resource)) {
            String line;
            boolean headerSeen = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (!headerSeen) {
                    headerSeen = true; // the "A,B,C,D,E" label row
                    continue;
                }
                String[] parts = line.split(",");
                assertEquals(nVars, parts.length, "malformed returns row: " + line);
                double[] row = new double[nVars];
                for (int j = 0; j < nVars; j++) {
                    row[j] = Double.parseDouble(parts[j]);
                }
                rows.add(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows.toArray(new double[0][]);
    }

    private static List<ExpectedWindow> loadExpected(String resource) {
        List<ExpectedWindow> out = new ArrayList<>();
        try (BufferedReader reader = open(resource)) {
            String line;
            boolean headerSeen = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (!headerSeen) {
                    headerSeen = true; // the "n_edges,density,avg_abs_corr" header
                    continue;
                }
                String[] parts = line.split(",");
                assertEquals(3, parts.length, "malformed expected row: " + line);
                int nEdges = Integer.parseInt(parts[0]);
                double density = Double.parseDouble(parts[1]);
                double avg = "NaN".equals(parts[2]) ? Double.NaN : Double.parseDouble(parts[2]);
                out.add(new ExpectedWindow(nEdges, density, avg));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static BufferedReader open(String resource) {
        InputStream in = RollingPearsonEngineOracleBTest.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("missing test fixture on classpath: " + resource);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private record ExpectedWindow(int nEdges, double density, double avgAbsCorr) {
    }
}
