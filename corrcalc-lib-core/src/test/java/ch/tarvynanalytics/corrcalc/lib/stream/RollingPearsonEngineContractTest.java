package ch.tarvynanalytics.corrcalc.lib.stream;

import ch.tarvynanalytics.corrcalc.lib.correlation.Correlations;
import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
import ch.tarvynanalytics.corrcalc.lib.exception.InvalidInputException;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's behavioral contract from the numerics spec, beyond the numeric
 * oracles (A/B): construction/argument validation, the warm-up and emit cadence,
 * the {@code seq}/{@code asOf} emission semantics (S1.7), the calendar-agnostic
 * session-boundary reset (S1.8), and the reserved snapshot-now/delta-ready shape.
 */
class RollingPearsonEngineContractTest {

    private static final String[] LABELS = {"A", "B", "C"};
    private static final CorrelationStreamListener NO_OP = (seq, asOf, pearson, labels) -> { };

    private record Emission(long seq, Instant asOf, DoubleMatrix matrix) {
    }

    private static CorrelationStreamListener recording(List<Emission> sink) {
        return (seq, asOf, pearson, labels) -> sink.add(new Emission(seq, asOf, pearson));
    }

    private static void feed(RollingCorrelationEngine engine, int bars, long seed) {
        java.util.Random random = new java.util.Random(seed);
        for (int t = 0; t < bars; t++) {
            double[] row = new double[LABELS.length];
            for (int j = 0; j < LABELS.length; j++) {
                row[j] = random.nextGaussian() * 0.02;
            }
            engine.onBar(Instant.ofEpochSecond(t), row);
        }
    }

    // --- construction validation ---

    @Test
    void pearson_NullLabels_Throws() {
        assertThrows(InvalidInputException.class,
                () -> RollingCorrelations.pearson(null, 10, NO_OP));
    }

    @Test
    void pearson_EmptyLabels_Throws() {
        assertThrows(InvalidInputException.class,
                () -> RollingCorrelations.pearson(new String[0], 10, NO_OP));
    }

    @Test
    void pearson_WindowBelowTwo_Throws() {
        assertThrows(InvalidInputException.class,
                () -> RollingCorrelations.pearson(LABELS, 1, NO_OP));
    }

    @Test
    void pearson_CadenceBelowOne_Throws() {
        assertThrows(InvalidInputException.class,
                () -> RollingCorrelations.pearson(LABELS, 10, 0, NO_OP, Profile.STANDARD));
    }

    @Test
    void pearson_NullListener_Throws() {
        assertThrows(InvalidInputException.class,
                () -> RollingCorrelations.pearson(LABELS, 10, null));
    }

    @Test
    void pearson_NullProfile_Throws() {
        assertThrows(InvalidInputException.class,
                () -> RollingCorrelations.pearson(LABELS, 10, 1, NO_OP, null));
    }

    // --- argument validation on ingest ---

    @Test
    void onBar_NullReturns_Throws() {
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, 10, NO_OP);
        assertThrows(InvalidInputException.class, () -> engine.onBar(Instant.EPOCH, (double[]) null));
    }

    @Test
    void onBar_NullFloatReturns_Throws() {
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, 10, NO_OP);
        assertThrows(InvalidInputException.class, () -> engine.onBar(Instant.EPOCH, (float[]) null));
    }

    @Test
    void onBar_WrongLengthReturns_Throws() {
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, 10, NO_OP);
        assertThrows(InvalidInputException.class, () -> engine.onBar(Instant.EPOCH, new double[]{1.0, 2.0}));
    }

    // --- warm-up + emission semantics (S1.7) ---

    @Test
    void onBar_BeforeWindowFills_EmitsNothing() {
        int window = 10;
        List<Emission> out = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, window, recording(out));
        feed(engine, window - 1, 1L);
        assertTrue(out.isEmpty(), "no snapshot may be emitted before the window has filled");
    }

    @Test
    void onBar_EveryBarCadence_FirstSnapshotAtWindowEndThenOnePerBar() {
        int window = 10;
        int bars = 25;
        List<Emission> out = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, window, recording(out));
        feed(engine, bars, 2L);

        assertEquals(bars - window + 1, out.size());
        // seq is monotonic from 0, one per emission
        for (int k = 0; k < out.size(); k++) {
            assertEquals(k, out.get(k).seq());
        }
        // asOf of emission k is the newest bar in the window: bar (window-1+k)
        for (int k = 0; k < out.size(); k++) {
            assertEquals(Instant.ofEpochSecond((long) window - 1 + k), out.get(k).asOf());
        }
    }

    @Test
    void onBar_CadenceGreaterThanOne_EmitsEveryNBarsWithMonotonicSeq() {
        int window = 5;
        int cadence = 3;
        int bars = 20;
        List<Emission> out = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(
                LABELS, window, cadence, recording(out), Profile.STANDARD);
        feed(engine, bars, 3L);

        // first emission at the first full window (bar window-1), then every
        // `cadence` bars; seq still increments once per emission, not per bar.
        assertTrue(out.size() >= 2);
        for (int k = 0; k < out.size(); k++) {
            assertEquals(k, out.get(k).seq());
        }
        long firstAsOf = out.get(0).asOf().getEpochSecond();
        assertEquals(window - 1L, firstAsOf);
        for (int k = 1; k < out.size(); k++) {
            assertEquals(cadence, out.get(k).asOf().getEpochSecond() - out.get(k - 1).asOf().getEpochSecond(),
                    "emissions after warm-up must be exactly `cadence` bars apart");
        }
    }

    @Test
    void onSnapshot_LabelsHandedBack_AreTheConstructionLabels() {
        int window = 4;
        List<String[]> seenLabels = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, window,
                (seq, asOf, pearson, labels) -> seenLabels.add(labels));
        feed(engine, window, 9L);
        assertEquals(1, seenLabels.size());
        assertSame(LABELS, seenLabels.get(0), "labels are handed back by reference, in column order");
    }

    // --- calendar-agnostic session boundary reset (S1.8) ---

    @Test
    void onSessionBoundary_ResetsWindowAndReWarmsWithoutCrossingTheGap() {
        int window = 8;
        int nVars = LABELS.length;
        // pre-gap bars, then a boundary, then post-gap bars from a different seed
        double[][] preGap = randomBars(10, nVars, 100L);
        double[][] postGap = randomBars(12, nVars, 200L);

        List<Emission> out = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, window, recording(out));
        for (double[] bar : preGap) {
            engine.onBar(Instant.ofEpochSecond(1), bar);
        }
        int emissionsBeforeReset = out.size();
        assertEquals(preGap.length - window + 1, emissionsBeforeReset);
        long seqBeforeReset = out.get(out.size() - 1).seq();

        engine.onSessionBoundary();

        // the next window-1 post-gap bars must emit NOTHING (re-warming)
        for (int t = 0; t < window - 1; t++) {
            engine.onBar(Instant.ofEpochSecond(1000 + t), postGap[t]);
        }
        assertEquals(emissionsBeforeReset, out.size(), "re-warming must not emit until the window refills");

        // the window-th post-gap bar emits, and that snapshot must equal a fresh
        // batch over ONLY the post-gap rows (no pre-gap row leaks across the gap)
        engine.onBar(Instant.ofEpochSecond(1000 + window - 1), postGap[window - 1]);
        assertEquals(emissionsBeforeReset + 1, out.size());
        Emission first = out.get(out.size() - 1);
        assertTrue(first.seq() > seqBeforeReset, "seq is NOT reset by a session boundary; it stays monotonic");

        DoubleMatrix postGapSlab = DoubleMatrix.zeros(window, nVars);
        for (int j = 0; j < nVars; j++) {
            for (int i = 0; i < window; i++) {
                postGapSlab.set(i, j, postGap[i][j]);
            }
        }
        DoubleMatrix batch = Correlations.pearson().calculate(postGapSlab);
        for (int i = 0; i < nVars; i++) {
            for (int j = 0; j < nVars; j++) {
                assertEquals(batch.get(i, j), first.matrix().get(i, j), 1e-12,
                        "post-gap snapshot must use only post-gap rows [" + i + "," + j + "]");
            }
        }
    }

    @Test
    void onSessionBoundary_OnFreshEngine_IsHarmlessNoOp() {
        List<Emission> out = new ArrayList<>();
        RollingCorrelationEngine engine = RollingCorrelations.pearson(LABELS, 5, recording(out));
        engine.onSessionBoundary(); // before any bar
        feed(engine, 5, 7L);
        assertEquals(1, out.size(), "a reset on an empty engine does not disturb the next warm-up");
    }

    // --- reserved snapshot-now / delta-ready shape ---

    @Test
    void onDelta_DefaultIsANoOp_AndDeltaRecordHoldsItsComponents() {
        // a listener that overrides only onSnapshot inherits a no-op onDelta;
        // invoking it must not throw (the v1 reserved contract).
        CorrelationStreamListener snapshotOnly = (seq, asOf, pearson, labels) -> { };
        DoubleMatrix changed = DoubleMatrix.zeros(2, 2);
        CorrelationDelta delta = new CorrelationDelta(changed, Instant.EPOCH);
        snapshotOnly.onDelta(0L, delta); // must be a no-op, never called in v1
        assertSame(changed, delta.changed());
        assertEquals(Instant.EPOCH, delta.asOf());
    }

    private static double[][] randomBars(int bars, int nVars, long seed) {
        java.util.Random random = new java.util.Random(seed);
        double[][] out = new double[bars][nVars];
        for (int t = 0; t < bars; t++) {
            for (int j = 0; j < nVars; j++) {
                out[t][j] = random.nextGaussian() * 0.02;
            }
        }
        return out;
    }
}
