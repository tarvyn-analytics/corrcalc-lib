package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;
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
 * Oracle C, the COR-375 contract extension: a non-finite ({@code NaN}/
 * {@code Infinity}) value in a variable's current window makes that variable
 * undefined -- its whole row/column, diagonal included, reads {@code NaN} --
 * exactly like the pre-existing zero-variance rule, and every other pair is
 * unaffected. Once the window again holds only finite values for that
 * variable it is defined again and matches a fresh two-pass Pearson within
 * {@code 1e-12}.
 * <p>
 * Oracle: independent of {@link AbstractRollingPearsonEngine} -- for each
 * emission a variable is undefined iff a non-finite value is among its last
 * {@code window} values; otherwise a hand-rolled two-pass Pearson over the
 * window, mirroring {@code RollingPearsonEngineOracleATest}'s naive reference.
 */
class RollingPearsonEngineOracleCTest {

    private static final double TOL = 1e-12;

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_FuzzedNonFiniteInputs_MatchesIndependentOracle(Profile profile) {
        int nVars = 6;
        int window = 32;
        int bars = 3000;
        Random random = new Random(20260904L);
        double[][] returns = randomLogReturns(random, bars, nVars);

        // ~2% of cells go non-finite, plus three guaranteed corners: two SEPARATE
        // spells on variable 0 (far enough apart to each fully drain out of the
        // window between them), and one bar where two variables are non-finite
        // simultaneously.
        double[] nonFiniteValues = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        int targetInjections = (int) Math.round(bars * nVars * 0.02);
        for (int c = 0; c < targetInjections; c++) {
            int t = window + 1 + random.nextInt(bars - window - 1);
            int j = random.nextInt(nVars);
            returns[t][j] = nonFiniteValues[random.nextInt(nonFiniteValues.length)];
        }
        int spell1 = 200;
        int spell2 = spell1 + window * 3;
        returns[spell1][0] = Double.NaN;
        returns[spell2][0] = Double.POSITIVE_INFINITY;
        int simulBar = 1500;
        returns[simulBar][1] = Double.NEGATIVE_INFINITY;
        returns[simulBar][2] = Double.NaN;

        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labelsFor(nVars), window, 1,
                recordingListener(snapshots), profile);
        for (int t = 0; t < bars; t++) {
            engine.onBar(Instant.ofEpochSecond(t), returns[t]);
        }

        assertEquals(bars - window + 1, snapshots.size());

        double worst = 0.0;
        boolean sawUndefined = false;
        for (int k = 0; k < snapshots.size(); k++) {
            boolean[] defined = new boolean[nVars];
            for (int j = 0; j < nVars; j++) {
                defined[j] = isDefined(returns, k, window, j);
            }
            DoubleMatrix actual = snapshots.get(k);
            for (int i = 0; i < nVars; i++) {
                for (int j = 0; j < nVars; j++) {
                    boolean expectedDefined = defined[i] && defined[j];
                    if (!expectedDefined) {
                        sawUndefined = true;
                    }
                    assertEquals(!expectedDefined, Double.isNaN(actual.get(i, j)),
                            "NaN-pattern mismatch at window " + k + " [" + i + "," + j + "]");
                    if (expectedDefined && i == j) {
                        assertEquals(1.0, actual.get(i, i), TOL, "diagonal at window " + k + " [" + i + "]");
                    } else if (expectedDefined) {
                        double expected = twoPassPearson(returns, k, window, i, j);
                        worst = Math.max(worst, Math.abs(expected - actual.get(i, j)));
                        assertEquals(expected, actual.get(i, j), TOL,
                                "value mismatch at window " + k + " [" + i + "," + j + "]");
                    }
                }
            }
        }

        assertTrue(sawUndefined, "expected at least one window to carry an undefined variable");
        assertTrue(worst <= TOL, "Oracle C worst finite deviation " + worst + " exceeded tolerance " + TOL);
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_NaNSpellDrainsOut_RowAndColumnRecoverAndOtherPairsUnaffected(Profile profile) {
        int nVars = 4;
        int window = 20;
        int k = 2;
        int totalBars = window * 8;
        Random random = new Random(4242L);
        double[][] realReturns = randomLogReturns(random, totalBars, nVars);
        // two spells on variable k, far enough apart to each fully drain
        int spellBar1 = window * 2;
        int spellBar2 = window * 5;
        realReturns[spellBar1][k] = Double.NaN;
        realReturns[spellBar2][k] = Double.POSITIVE_INFINITY;

        // reference stream: identical except the spell bars for k are replaced
        // by a finite placeholder -- a second, independent engine instance that
        // proves every pair NOT touching k is untouched by k's non-finite entries.
        double[][] cleanReturns = copy(realReturns);
        cleanReturns[spellBar1][k] = 0.0;
        cleanReturns[spellBar2][k] = 0.0;

        String[] labels = labelsFor(nVars);
        List<DoubleMatrix> realSnapshots = new ArrayList<>();
        List<DoubleMatrix> cleanSnapshots = new ArrayList<>();
        RollingCorrelationEngine realEngine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(realSnapshots), profile);
        RollingCorrelationEngine cleanEngine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(cleanSnapshots), profile);
        for (int t = 0; t < totalBars; t++) {
            realEngine.onBar(Instant.ofEpochSecond(t), realReturns[t]);
            cleanEngine.onBar(Instant.ofEpochSecond(t), cleanReturns[t]);
        }

        assertEquals(cleanSnapshots.size(), realSnapshots.size());
        int recoveries = 0;
        int undefinedWindows = 0;
        for (int idx = 0; idx < realSnapshots.size(); idx++) {
            boolean kDefined = isDefined(realReturns, idx, window, k);
            DoubleMatrix real = realSnapshots.get(idx);
            DoubleMatrix clean = cleanSnapshots.get(idx);
            for (int i = 0; i < nVars; i++) {
                for (int j = 0; j < nVars; j++) {
                    if (i == k || j == k) {
                        assertEquals(!kDefined, Double.isNaN(real.get(i, j)),
                                "row/col " + k + " NaN-state mismatch at window " + idx + " [" + i + "," + j + "]");
                    } else {
                        assertEquals(clean.get(i, j), real.get(i, j), 0.0,
                                "pair not touching k diverged at window " + idx + " [" + i + "," + j + "]");
                    }
                }
            }
            if (kDefined) {
                recoveries++;
                for (int other = 0; other < nVars; other++) {
                    if (other == k) {
                        continue;
                    }
                    double expected = twoPassPearson(realReturns, idx, window, k, other);
                    assertEquals(expected, real.get(k, other), TOL,
                            "recovered column " + k + " mismatch at window " + idx + " [" + other + "]");
                }
            } else {
                undefinedWindows++;
            }
        }

        assertTrue(undefinedWindows >= 2 * window,
                "expected both spells to each keep column " + k + " undefined for a full window");
        assertTrue(recoveries > 0, "expected column " + k + " to recover to defined at least once");
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onBar_SpellLongerThanWindowWrapsBuffer_TaintedColumnRecoversExactlyWindowMinusOneEmissionsAfterLastBar(
            Profile profile) {
        // A spell of 27 bars against a window of 10 spans 2.7 window-cycles, so the
        // circular buffer wraps several times while it is inside the window (real-world
        // shape: FTTUSDT was non-finite for ~10 months against a 480-bar window). Neither
        // boundary is aligned to a window/writeSlot cycle (33 % 10 == 3, 59 % 10 == 9),
        // so the spell both starts and ends mid-buffer.
        int nVars = 4;
        int window = 10;
        int k = 2;
        int spellStart = 33;
        int spellLen = 27;
        int spellEnd = spellStart + spellLen - 1;
        int totalBars = 100;
        Random random = new Random(90210L);

        double[][] realReturns = randomLogReturns(random, totalBars, nVars);
        for (int t = spellStart; t <= spellEnd; t++) {
            realReturns[t][k] = Double.NaN;
        }
        // reference stream: identical except the spell is replaced by a finite
        // placeholder for k -- proves a spell many times longer than the window still
        // never leaks into a pair that does not touch k.
        double[][] cleanReturns = copy(realReturns);
        for (int t = spellStart; t <= spellEnd; t++) {
            cleanReturns[t][k] = 0.0;
        }

        String[] labels = labelsFor(nVars);
        List<DoubleMatrix> realSnapshots = new ArrayList<>();
        List<DoubleMatrix> cleanSnapshots = new ArrayList<>();
        RollingCorrelationEngine realEngine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(realSnapshots), profile);
        RollingCorrelationEngine cleanEngine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(cleanSnapshots), profile);
        for (int t = 0; t < totalBars; t++) {
            realEngine.onBar(Instant.ofEpochSecond(t), realReturns[t]);
            cleanEngine.onBar(Instant.ofEpochSecond(t), cleanReturns[t]);
        }
        assertEquals(cleanSnapshots.size(), realSnapshots.size());

        // The property that matters: recovery is driven purely by the window draining,
        // not by anything incidental to how long the spell was. NaN runs from the
        // emission triggered by the FIRST non-finite bar (window = [spellStart-window+1,
        // spellStart], the earliest window containing it) through exactly window-1
        // MORE emissions after the emission triggered by the LAST non-finite bar.
        int lastBarEmission = spellEnd - window + 1;
        int firstNaNEmission = spellStart - window + 1;
        int lastNaNEmission = lastBarEmission + (window - 1);
        assertEquals(spellEnd, lastNaNEmission,
                "sanity: window-1 emissions after the last bar's own emission must land on spellEnd");
        assertTrue(firstNaNEmission >= 0, "test setup: spell must start after the first full window");
        assertTrue(lastNaNEmission + window < realSnapshots.size(),
                "test setup: need enough post-spell bars to observe full recovery");

        for (int j = 0; j < realSnapshots.size(); j++) {
            boolean expectNaN = j >= firstNaNEmission && j <= lastNaNEmission;
            assertEquals(expectNaN, !isDefined(realReturns, j, window, k),
                    "formula disagrees with the window-scan oracle at emission " + j);
            DoubleMatrix real = realSnapshots.get(j);
            DoubleMatrix clean = cleanSnapshots.get(j);
            for (int i = 0; i < nVars; i++) {
                for (int col = 0; col < nVars; col++) {
                    if (i == k || col == k) {
                        assertEquals(expectNaN, Double.isNaN(real.get(i, col)),
                                "row/col " + k + " NaN-state mismatch at emission " + j + " [" + i + "," + col + "]");
                    } else {
                        assertEquals(clean.get(i, col), real.get(i, col), 0.0,
                                "pair not touching " + k + " diverged at emission " + j + " [" + i + "," + col + "]");
                    }
                }
            }
            if (!expectNaN) {
                for (int other = 0; other < nVars; other++) {
                    if (other == k) {
                        continue;
                    }
                    double expected = twoPassPearson(realReturns, j, window, k, other);
                    assertEquals(expected, real.get(k, other), TOL,
                            "recovered column " + k + " mismatch at emission " + j + " [" + other + "]");
                }
            }
        }
    }

    @Test
    void onBar_FloatPathNaNSpell_RowAndColumnRecoverToOracle() {
        int nVars = 3;
        int window = 12;
        int k = 1;
        int totalBars = window * 4;
        Random random = new Random(777L);
        double[][] source = randomLogReturns(random, totalBars, nVars);
        source[window * 2][k] = Double.NaN;
        // the engine narrows each bar to float and widens back before accumulating
        // (invariant #4); the oracle must reason about the SAME narrowed values.
        double[][] narrowed = narrowToFloat(source);

        String[] labels = labelsFor(nVars);
        List<DoubleMatrix> snapshots = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, window, 1,
                recordingListener(snapshots), Profile.STANDARD);
        for (int t = 0; t < totalBars; t++) {
            float[] row = new float[nVars];
            for (int j = 0; j < nVars; j++) {
                row[j] = (float) source[t][j];
            }
            engine.onBar(Instant.ofEpochSecond(t), row);
        }

        boolean sawUndefined = false;
        boolean sawRecovered = false;
        for (int idx = 0; idx < snapshots.size(); idx++) {
            boolean kDefined = isDefined(narrowed, idx, window, k);
            DoubleMatrix actual = snapshots.get(idx);
            for (int other = 0; other < nVars; other++) {
                if (other == k) {
                    continue;
                }
                assertEquals(!kDefined, Double.isNaN(actual.get(k, other)),
                        "float path row/col " + k + " NaN-state mismatch at window " + idx);
                if (kDefined) {
                    double expected = twoPassPearson(narrowed, idx, window, k, other);
                    assertEquals(expected, actual.get(k, other), TOL,
                            "float path recovered column " + k + " mismatch at window " + idx);
                    sawRecovered = true;
                } else {
                    sawUndefined = true;
                }
            }
        }

        assertTrue(sawUndefined, "expected the float-path spell to make column " + k + " undefined at least once");
        assertTrue(sawRecovered, "expected column " + k + " to recover to defined at least once");
    }

    @ParameterizedTest
    @EnumSource(Profile.class)
    void onSessionBoundary_AfterNaNSpell_NextFullWindowIsDefined(Profile profile) {
        int nVars = 3;
        int window = 10;
        Random random = new Random(31415L);

        List<DoubleMatrix> out = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(labelsFor(nVars), window, 1,
                recordingListener(out), profile);
        for (int t = 0; t < window; t++) {
            double[] row = randomRow(random, nVars);
            if (t == window / 2) {
                row[1] = Double.NaN;
            }
            engine.onBar(Instant.ofEpochSecond(t), row);
        }
        assertTrue(Double.isNaN(out.get(out.size() - 1).get(1, 0)), "sanity: variable 1 must be undefined pre-reset");

        engine.onSessionBoundary();

        double[][] postReset = new double[window][];
        for (int t = 0; t < window; t++) {
            postReset[t] = randomRow(random, nVars);
        }
        int before = out.size();
        for (int t = 0; t < window; t++) {
            engine.onBar(Instant.ofEpochSecond(1000 + t), postReset[t]);
        }
        assertEquals(before + 1, out.size());

        DoubleMatrix snapshot = out.get(out.size() - 1);
        for (int i = 0; i < nVars; i++) {
            for (int j = 0; j < nVars; j++) {
                assertFalse(Double.isNaN(snapshot.get(i, j)),
                        "no residual NaN after session boundary [" + i + "," + j + "]");
                if (i != j) {
                    double expected = twoPassPearson(postReset, 0, window, i, j);
                    assertEquals(expected, snapshot.get(i, j), TOL, "[" + i + "," + j + "]");
                }
            }
        }
    }

    // --- shared helpers ---

    private static CorrelationStreamListener recordingListener(List<DoubleMatrix> sink) {
        return (seq, asOf, pearson, labels) -> sink.add(pearson);
    }

    private static boolean isDefined(double[][] returns, int start, int window, int col) {
        for (int t = start; t < start + window; t++) {
            if (!Double.isFinite(returns[t][col])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Naive two-pass Pearson correlation over {@code returns[start, start+window)},
     * hand-rolled and independent of every other implementation in this codebase.
     */
    private static double twoPassPearson(double[][] returns, int start, int window, int a, int b) {
        double mx = 0;
        double my = 0;
        for (int t = start; t < start + window; t++) {
            mx += returns[t][a];
            my += returns[t][b];
        }
        mx /= window;
        my /= window;
        double cov = 0;
        double vx = 0;
        double vy = 0;
        for (int t = start; t < start + window; t++) {
            double dx = returns[t][a] - mx;
            double dy = returns[t][b] - my;
            cov += dx * dy;
            vx += dx * dx;
            vy += dy * dy;
        }
        return cov / Math.sqrt(vx * vy);
    }

    private static double[][] randomLogReturns(Random random, int bars, int nVars) {
        double[][] out = new double[bars][nVars];
        for (int t = 0; t < bars; t++) {
            out[t] = randomRow(random, nVars);
        }
        return out;
    }

    private static double[] randomRow(Random random, int nVars) {
        double[] row = new double[nVars];
        for (int j = 0; j < nVars; j++) {
            row[j] = random.nextGaussian() * 0.02;
        }
        return row;
    }

    private static double[][] copy(double[][] source) {
        double[][] out = new double[source.length][];
        for (int t = 0; t < source.length; t++) {
            out[t] = source[t].clone();
        }
        return out;
    }

    private static double[][] narrowToFloat(double[][] source) {
        double[][] out = new double[source.length][source[0].length];
        for (int t = 0; t < source.length; t++) {
            for (int j = 0; j < source[t].length; j++) {
                out[t][j] = (float) source[t][j];
            }
        }
        return out;
    }

    private static String[] labelsFor(int n) {
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = "v" + i;
        }
        return labels;
    }
}
