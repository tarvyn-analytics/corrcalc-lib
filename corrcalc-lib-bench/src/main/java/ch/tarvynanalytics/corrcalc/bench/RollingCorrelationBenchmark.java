package ch.tarvynanalytics.corrcalc.bench;

import ch.tarvynanalytics.corrcalc.lib.correlation.CorrelationCalculator;
import ch.tarvynanalytics.corrcalc.lib.correlation.Correlations;
import ch.tarvynanalytics.corrcalc.lib.correlation.Profile;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.stream.CorrelationStreamListener;
import ch.tarvynanalytics.corrcalc.lib.stream.RollingCorrelationEngine;
import ch.tarvynanalytics.corrcalc.lib.stream.RollingCorrelations;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Streaming rolling-window Pearson engine: cost of one ingested bar.
 *
 * <p>The unit of work is a single {@link RollingCorrelationEngine#onBar} call. The
 * incremental rank-one window slide is {@code O(N^2)} in the variable count
 * {@code N} and <b>independent of the window width {@code W}</b> — that is the win
 * over recomputing the matrix, which costs {@code O(W*N^2)} per bar. Three modes are
 * measured so the README can quote both the absolute throughput and the speed-up:
 * <ul>
 *   <li>{@link #slide} — the incremental slide only (emit cadence set high enough it
 *       never fires), isolating the per-bar update cost;
 *   <li>{@link #slideAndSnapshot} — slide plus assembling and emitting the full N×N
 *       matrix snapshot every bar (cadence 1), the upper-bound streaming cost;
 *   <li>{@link #recomputeMatrix} — the naive baseline: recompute the whole Pearson
 *       matrix over the W-row window every bar with the batch calculator.
 * </ul>
 * Pair the run with {@code -prof gc} to read allocation per bar: ~0 for {@link #slide},
 * {@code N*N*8} bytes (the fresh snapshot) for {@link #slideAndSnapshot}.
 *
 * <p>{@code window} is a secondary axis purely to confirm the slide cost is flat in
 * {@code W} while the recompute baseline scales with it; the engine's compute does not
 * depend on {@code W} (only its memory footprint does). v1 is {@code STANDARD}-only
 * (the HIGH_PERFORMANCE / VECTORIZED profiles resolve to the same scalar engine), so
 * profile is not swept here.
 *
 * <p>Data is seeded log-return-scale Gaussian noise (the engine's input domain),
 * generated once per trial.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {"-Xmx2g"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RollingCorrelationBenchmark {

    /** Power of two so the per-op bar index is a cheap mask, not a modulo. */
    private static final int POOL = 8192;
    private static final Instant TS = Instant.EPOCH;

    @Param({"16", "50", "100"})
    private int variables;

    @Param({"120", "480"})
    private int window;

    private double[][] bars;
    private int idx;

    private RollingCorrelationEngine slideEngine;     // emit cadence MAX_VALUE -> never emits
    private RollingCorrelationEngine snapshotEngine;  // cadence 1 -> emits every bar
    private DoubleMatrix lastSnapshot;

    private CorrelationCalculator batch;
    private DoubleMatrix windowSlab;                  // a fixed W x N window for the naive baseline

    @Setup
    public void setUp() {
        Random random = new Random(42L);
        bars = new double[POOL][variables];
        for (int b = 0; b < POOL; b++) {
            for (int j = 0; j < variables; j++) {
                bars[b][j] = random.nextGaussian() * 0.02; // log-return scale
            }
        }

        String[] labels = new String[variables];
        for (int i = 0; i < variables; i++) {
            labels[i] = "v" + i;
        }
        CorrelationStreamListener sink = (seq, asOf, pearson, lbls) -> lastSnapshot = pearson;
        slideEngine = RollingCorrelations.pearson(labels, window, Integer.MAX_VALUE, sink, Profile.STANDARD);
        snapshotEngine = RollingCorrelations.pearson(labels, window, 1, sink, Profile.STANDARD);
        // warm both engines past the window so every measured bar is steady-state
        for (int b = 0; b < window; b++) {
            double[] bar = bars[b & (POOL - 1)];
            slideEngine.onBar(TS, bar);
            snapshotEngine.onBar(TS, bar);
        }
        idx = 0;

        batch = Correlations.pearson(Profile.STANDARD);
        double[] slab = new double[window * variables];
        for (int i = 0; i < slab.length; i++) {
            slab[i] = random.nextGaussian() * 0.02;
        }
        windowSlab = DoubleMatrix.columnMajor(slab, window, variables);
    }

    private double[] nextBar() {
        return bars[(idx++) & (POOL - 1)];
    }

    @Benchmark
    public void slide(Blackhole bh) {
        slideEngine.onBar(TS, nextBar());
        bh.consume(slideEngine);
    }

    @Benchmark
    public DoubleMatrix slideAndSnapshot() {
        snapshotEngine.onBar(TS, nextBar());
        return lastSnapshot;
    }

    @Benchmark
    public DoubleMatrix recomputeMatrix() {
        return batch.calculate(windowSlab);
    }
}
