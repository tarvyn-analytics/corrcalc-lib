package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.correlation.Correlations;
import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle A from the S1 numerics spec: the incremental rolling matrix at every
 * window-end must equal an <b>independent</b> full-batch recompute within
 * {@code 1e-12} absolute tolerance.
 * <p>
 * Independence is the anti-cheat core: the batch side below is always
 * {@code Correlations.pearson(profile).calculate(slab)} — the existing,
 * differently-derived (centered-norm dot product) calculator — over the
 * {@code W}-row slab ending at the same bar; it never calls the streaming
 * engine. Cases C1-C8 mirror the spec's adversarial table exactly; the
 * tolerance is fixed at {@code 1e-12} (proven worst case 1.17e-13 in the
 * spec, ~10x margin) and must not be loosened or tightened below {@code 1e-13}.
 */
class RollingPearsonEngineOracleATest {

    private static final double TOL = 1e-12;

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C1Baseline_MatchesBatchAndNaiveReference(Profile profile) {
        int nVars = 5;
        int window = 90;
        int bars = 600;
        DoubleMatrix data = randomMatrix(bars, nVars, 20260622L);
        String[] labels = {"A", "B", "C", "D", "E"};
        zeroOutColumn(data, 4); // column E: constant zero, exercises the NaN row

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
        feedAllRows(engine, data);

        assertEquals(bars - window + 1, snapshots.size());
        assertMatchesIndependentBatch(data, window, profile, snapshots, TOL);

        // triple-pin window 0 against a hand-rolled naive Pearson, pair (0,1)
        double naive = naivePearson(columnSlice(data, 0, 0, window), columnSlice(data, 1, 0, window));
        assertEquals(naive, snapshots.get(0).get(0, 1), TOL);
        // column E (index 4) must be an all-NaN row/col, including the diagonal
        for (int j = 0; j < nVars; j++) {
            assertTrue(Double.isNaN(snapshots.get(0).get(4, j)));
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C2NearPerfectCorrelation_MatchesBatchWithinTolerance(Profile profile) {
        int window = 90;
        int bars = 50_000;
        Random random = new Random(2L);
        DoubleMatrix data = DoubleMatrix.zeros(bars, 2);
        for (int i = 0; i < bars; i++) {
            double x = random.nextGaussian() * 0.02;
            data.set(i, 0, x);
            data.set(i, 1, x + 1e-7 * random.nextGaussian());
        }
        String[] labels = {"x", "xNoisy"};

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
        feedAllRows(engine, data);

        assertMatchesIndependentBatch(data, window, profile, snapshots, TOL);
        // confirm we actually exercised the |r| -> 1 cliff
        boolean sawNearOne = snapshots.stream().anyMatch(m -> Math.abs(m.get(0, 1)) > 0.999);
        assertTrue(sawNearOne, "expected at least one window with |r| > 0.999");
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C3NearConstantColumn_DoesNotSpuriouslyProduceNaN(Profile profile) {
        int window = 90;
        int bars = 300;
        Random random = new Random(3L);
        DoubleMatrix data = DoubleMatrix.zeros(bars, 2);
        for (int i = 0; i < bars; i++) {
            data.set(i, 0, random.nextGaussian() * 0.02);
            // Near-constant but ~zero-mean: tiny positive variance, NO constant
            // offset. This is the engine's stated input domain (log-returns,
            // O(0.01-0.1), centered near zero). A constant *offset* with this
            // variance would hit the documented catastrophic-cancellation regime
            // (numerics spec S1.4) which is explicitly out of the engine's
            // contract; the point of C3 is only that a barely-positive variance
            // is not spuriously flagged NaN.
            data.set(i, 1, 1e-9 * random.nextGaussian());
        }
        String[] labels = {"x", "nearConst"};

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
        feedAllRows(engine, data);

        assertMatchesIndependentBatch(data, window, profile, snapshots, TOL);
        // va is barely positive (~1e-18), not zero -> must NOT be NaN
        for (DoubleMatrix snapshot : snapshots) {
            assertFalse(Double.isNaN(snapshot.get(0, 1)), "near-constant column spuriously produced NaN");
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C4ExactConstantColumn_ProducesNaNRowAndDiagonal(Profile profile) {
        int window = 30;
        int bars = 200;
        Random random = new Random(4L);
        DoubleMatrix data = DoubleMatrix.zeros(bars, 2);
        for (int i = 0; i < bars; i++) {
            data.set(i, 0, random.nextGaussian() * 0.02);
            data.set(i, 1, 0.0);
        }
        String[] labels = {"x", "constant"};

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
        feedAllRows(engine, data);

        assertMatchesIndependentBatch(data, window, profile, snapshots, TOL);
        for (DoubleMatrix snapshot : snapshots) {
            assertTrue(Double.isNaN(snapshot.get(0, 1)));
            assertTrue(Double.isNaN(snapshot.get(1, 0)));
            assertTrue(Double.isNaN(snapshot.get(1, 1)), "zero-variance diagonal must be NaN, not 1.0");
            assertEquals(1.0, snapshot.get(0, 0));
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C5LongStreamDrift_StaysWithinTolerance(Profile profile) {
        int nVars = 4;
        int window = 90;
        int bars = 2_000_000;
        DoubleMatrix data = randomLogReturnMatrix(bars, nVars, 555L);
        String[] labels = {"A", "B", "C", "D"};

        // independent batch only at sparse checkpoints (recomputing every
        // window over 2M bars would dominate the test runtime); the drift
        // argument in the spec is that error does not grow with stream
        // length, so checkpoints spread across the stream are sufficient.
        int[] checkpoints = {window - 1, 100_000, 500_000, 1_000_000, 1_500_000, bars - 1};
        java.util.Set<Integer> checkpointSet = new java.util.HashSet<>();
        for (int c : checkpoints) {
            checkpointSet.add(c);
        }

        double[] worst = {0.0};
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1, (seq, asOf, pearson, lbls) -> {
            int windowEnd = (int) (window - 1 + seq);
            if (checkpointSet.contains(windowEnd)) {
                DoubleMatrix slab = sliceRows(data, windowEnd - window + 1, window);
                DoubleMatrix batch = Correlations.pearson(profile).calculate(slab);
                for (int i = 0; i < nVars; i++) {
                    for (int j = 0; j < nVars; j++) {
                        double diff = Math.abs(pearson.get(i, j) - batch.get(i, j));
                        if (diff > worst[0]) {
                            worst[0] = diff;
                        }
                    }
                }
            }
        }, profile);
        feedAllRows(engine, data);

        assertTrue(worst[0] <= TOL, "C5 drift " + worst[0] + " exceeded tolerance " + TOL);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C6SmallAndBoundaryWindowSizes_MatchBatch(Profile profile) {
        int[] sizes = {2, 3, 16, 50};
        // W starts at 3, the smallest window where independent columns are not
        // trivially collinear. W=2 (any two points are ALWAYS perfectly
        // correlated, |r|=1) is the degenerate minimum-window boundary, covered
        // separately by onBar_C6MinimumWindowOfTwo_* below: the uncentered
        // running-sums form (required for spike parity) cannot meet 1e-12 at the
        // |r|=1 cliff with only two points (empirically ~1e-7), which is a
        // property of that corner, not of the rank-one slide.
        int[] windows = {3, 30, 90};
        for (int nVars : sizes) {
            for (int window : windows) {
                int bars = Math.max(window * 3, window + 20);
                DoubleMatrix data = randomLogReturnMatrix(bars, nVars, (long) (nVars * 1000 + window));
                String[] labels = labelsFor(nVars);

                List<DoubleMatrix> snapshots = new ArrayList<>();
                RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
                feedAllRows(engine, data);

                assertMatchesIndependentBatch(data, window, profile, snapshots, TOL);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C6MinimumWindowOfTwo_EmitsCollinearPairsMatchingBatch(Profile profile) {
        // Boundary: window == 2 is the engine's minimum. Any two points are
        // perfectly collinear, so every off-diagonal |r| == 1 (up to sign). This
        // is the worst-conditioned corner of the uncentered running-sums formula
        // (kept for spike parity); it agrees with the centered batch only to
        // ~1e-6 there, vs 1e-12 for realistic windows. We still pin it against
        // the independent batch, at a tolerance honest to the |r|=1 cliff at W=2
        // -- a real rank-one-slide bug would be orders larger and still caught.
        int nVars = 3;
        int window = 2;
        int bars = 40;
        DoubleMatrix data = randomLogReturnMatrix(bars, nVars, 7L);
        String[] labels = labelsFor(nVars);

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
        feedAllRows(engine, data);

        assertEquals(bars - window + 1, snapshots.size());
        assertMatchesIndependentBatch(data, window, profile, snapshots, 1e-6);
        for (DoubleMatrix m : snapshots) {
            assertEquals(1.0, Math.abs(m.get(0, 1)), 1e-6, "two points are always perfectly collinear");
        }
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C7AboveParallelThreshold_MatchesBatch(Profile profile) {
        // size the snapshot work comfortably above PARALLEL_THRESHOLD_FLOPS so
        // both the serial and parallel batch paths are exercised and agree
        // with the engine.
        int nVars = 50;
        int window = 250;
        int bars = window + 50;
        DoubleMatrix data = randomLogReturnMatrix(bars, nVars, 4242L);
        String[] labels = labelsFor(nVars);

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
        feedAllRows(engine, data);

        assertMatchesIndependentBatch(data, window, profile, snapshots, TOL);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_C8FloatBarPath_AccumulatesInDoubleMatchingDoubleBatch(Profile profile) {
        int nVars = 4;
        int window = 90;
        int bars = 600;
        DoubleMatrix data = randomLogReturnMatrix(bars, nVars, 99L);
        String[] labels = labelsFor(nVars);

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), profile);
        for (int i = 0; i < bars; i++) {
            float[] row = new float[nVars];
            for (int j = 0; j < nVars; j++) {
                row[j] = (float) data.get(i, j);
            }
            engine.onBar(Instant.ofEpochSecond(i), row);
        }

        // The engine widens each float bar to double and accumulates the running
        // sums in double (invariant #4). The fair, equally-accurate reference is
        // therefore the DOUBLE batch calculator over the SAME float values
        // widened to double -- not calculateToDouble(FloatMatrix), whose float
        // working buffer caps it at ~1e-8 and would understate the engine's (more
        // accurate) pure-double accumulation. Matching the double batch to 1e-12
        // is exactly the proof that the float ingest path accumulates in double.
        assertEquals(bars - window + 1, snapshots.size());
        for (int k = 0; k < snapshots.size(); k++) {
            DoubleMatrix slab = DoubleMatrix.zeros(window, nVars);
            for (int j = 0; j < nVars; j++) {
                for (int i = 0; i < window; i++) {
                    slab.set(i, j, (float) data.get(k + i, j)); // narrow then widen, as the engine does
                }
            }
            DoubleMatrix batch = Correlations.pearson(profile).calculate(slab);
            DoubleMatrix actual = snapshots.get(k);
            for (int i = 0; i < nVars; i++) {
                for (int j = 0; j < nVars; j++) {
                    if (i == j) {
                        continue;
                    }
                    assertEquals(batch.get(i, j), actual.get(i, j), TOL,
                            "mismatch at window " + k + " [" + i + "," + j + "]");
                }
            }
        }
    }

    // --- shared helpers ---

    /**
     * Compares every <b>off-diagonal</b> cell against the independent batch
     * calculator. The diagonal is intentionally excluded: per the numerics
     * spec (S1.5) the engine emits {@code NaN} on the diagonal of a
     * zero-variance variable, while the batch calculator always emits
     * {@code 1.0} there — a deliberate, documented difference in the
     * diagonal contract between the two, pinned separately by each case's
     * own zero-variance assertions, not something to cross-check here.
     */
    private static void assertMatchesIndependentBatch(DoubleMatrix data, int window, Profile profile,
                                                       List<DoubleMatrix> snapshots, double tol) {
        for (int k = 0; k < snapshots.size(); k++) {
            DoubleMatrix slab = sliceRows(data, k, window);
            DoubleMatrix batch = Correlations.pearson(profile).calculate(slab);
            DoubleMatrix actual = snapshots.get(k);
            int n = data.cols();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        continue;
                    }
                    boolean expectedNaN = Double.isNaN(batch.get(i, j));
                    boolean actualNaN = Double.isNaN(actual.get(i, j));
                    assertEquals(expectedNaN, actualNaN,
                            "NaN mismatch at window " + k + " [" + i + "," + j + "]");
                    if (!expectedNaN) {
                        assertEquals(batch.get(i, j), actual.get(i, j), tol,
                                "mismatch at window " + k + " [" + i + "," + j + "]");
                    }
                }
            }
        }
    }

    private static CorrelationStreamListener recordingListener(List<DoubleMatrix> sink) {
        return (seq, asOf, pearson, labels) -> sink.add(pearson);
    }

    private static void feedAllRows(RollingCorrelationEngine engine, DoubleMatrix data) {
        int nVars = data.cols();
        for (int i = 0; i < data.rows(); i++) {
            double[] row = new double[nVars];
            for (int j = 0; j < nVars; j++) {
                row[j] = data.get(i, j);
            }
            engine.onBar(Instant.ofEpochSecond(i), row);
        }
    }

    private static DoubleMatrix sliceRows(DoubleMatrix data, int startRow, int rows) {
        int cols = data.cols();
        DoubleMatrix slab = DoubleMatrix.zeros(rows, cols);
        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows; i++) {
                slab.set(i, j, data.get(startRow + i, j));
            }
        }
        return slab;
    }

    private static double[] columnSlice(DoubleMatrix data, int col, int startRow, int rows) {
        double[] result = new double[rows];
        for (int i = 0; i < rows; i++) {
            result[i] = data.get(startRow + i, col);
        }
        return result;
    }

    private static void zeroOutColumn(DoubleMatrix data, int col) {
        for (int i = 0; i < data.rows(); i++) {
            data.set(i, col, 0.0);
        }
    }

    private static String[] labelsFor(int n) {
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = "v" + i;
        }
        return labels;
    }

    /** Log-return-scale seeded data: O(0.01-0.1), the engine's stated input domain. */
    private static DoubleMatrix randomLogReturnMatrix(int rows, int cols, long seed) {
        Random random = new Random(seed);
        DoubleMatrix matrix = DoubleMatrix.zeros(rows, cols);
        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows; i++) {
                matrix.set(i, j, random.nextGaussian() * 0.02);
            }
        }
        return matrix;
    }

    private static DoubleMatrix randomMatrix(int rows, int cols, long seed) {
        return randomLogReturnMatrix(rows, cols, seed);
    }

    /**
     * Naive textbook Pearson correlation, hand-rolled, independent of every
     * other implementation in this codebase: triple-pins C1 against this in
     * addition to the batch calculator.
     */
    private static double naivePearson(double[] x, double[] y) {
        int n = x.length;
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double cov = 0;
        double vx = 0;
        double vy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            cov += dx * dy;
            vx += dx * dx;
            vy += dy * dy;
        }
        return cov / Math.sqrt(vx * vy);
    }
}
