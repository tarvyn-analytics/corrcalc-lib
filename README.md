# CorrCalc Lib

> Feature-complete and stable; maintained as-is.

An all-purpose correlation calculator for the JVM: a pure Java library that
computes correlation matrices from any numerical dataset — Pearson, partial,
Spearman and Kendall, in batch or incrementally over a live data stream.
Zero runtime dependencies, built for speed and low memory overhead.

**Tech stack:** Java 21+ at runtime (the optional `VECTORIZED` profile needs
25+), JDK 25 to build, Maven, JUnit 5, JaCoCo (80% line / 70% branch minimum,
enforced by the build).

## Quick start

```bash
./mvnw clean verify     # build, run all tests, enforce coverage
```

As a dependency — available on [Maven Central](https://central.sonatype.com/artifact/io.github.tarvyn-analytics.corrcalc/corrcalc-lib-core), no credentials needed:

```xml
<dependency>
    <groupId>io.github.tarvyn-analytics.corrcalc</groupId>
    <artifactId>corrcalc-lib-core</artifactId>
    <version>1.2.2</version>
</dependency>
```

```java
import ch.tarvynanalytics.corrcalc.lib.correlation.Correlations;
import ch.tarvynanalytics.corrcalc.lib.io.CsvMatrixReader;
import ch.tarvynanalytics.corrcalc.lib.matrix.DoubleMatrix;
import ch.tarvynanalytics.corrcalc.lib.prep.Preparers;

// 1) Load data: one observation per row, one variable per column.
//    Either from arrays...
DoubleMatrix data = DoubleMatrix.fromRows(new double[][]{
        {1.0, 2.0, 0.5},
        {2.0, 4.1, 0.9},
        {3.0, 6.2, 0.1}
});
//    ...or from a whitespace-separated file ("NaN" marks missing values)
DoubleMatrix fromFile = new CsvMatrixReader().read(inputStream, numRows, numCols);

// 2) Optionally prepare the data
var preparation = Preparers.pipeline(Preparers.imputeMean(), Preparers.standardize());
DoubleMatrix prepared = preparation.prepare(data);

// 3) Calculate the correlation matrix (p x p, symmetric, ones on the diagonal).
//    Optionally pick a calculation profile (default: Profile.STANDARD)
DoubleMatrix corr = Correlations.pearson().calculate(prepared);
double r01 = corr.get(0, 1);

//    ...or the partial correlation matrix (effect of all other variables removed)
DoubleMatrix partial = Correlations.partial().calculate(prepared);

//    ...or the Spearman rank correlation matrix (monotonic association)
DoubleMatrix spearman = Correlations.spearman().calculate(prepared);

//    ...or the Kendall tau-b rank correlation matrix (ordinal association)
DoubleMatrix kendall = Correlations.kendall().calculate(prepared);
```

For a **live stream** of observations (one value per variable per tick — market
bars, sensor readings, any rolling feed), the `stream` package maintains the
rolling-window Pearson matrix incrementally and emits a snapshot on a cadence —
instead of recomputing the whole matrix each time:

```java
import ch.tarvynanalytics.corrcalc.lib.stream.RollingCorrelations;
import ch.tarvynanalytics.corrcalc.lib.stream.RollingCorrelationEngine;

String[] labels = {"BTC", "ETH", "SOL"};
RollingCorrelationEngine engine = RollingCorrelations.pearson(labels, /*window=*/480,
        (seq, asOf, pearson, lbls) -> publish(asOf, pearson));   // snapshot listener

engine.onBar(timestamp, new double[]{btcRet, ethRet, solRet});   // O(1) per pair rank-one slide
engine.onSessionBoundary();   // calendar-agnostic: reset the window at a session gap
```

The engine is stateful and single-writer (one engine per symbol basket and one
sampling frequency); the running sums always accumulate in `double`, even on the
`float[]` ingest path. See the `stream` package javadoc for the snapshot-now /
delta-ready listener contract and the NaN rules for zero-variance windows.

**Input contract (read this before feeding it data).** The engine is built for
**near-zero-mean series** — financial returns are the canonical example — and
its accuracy guarantee holds only inside that domain:

- **Feed changes, not levels.** Each value must be a per-tick **change** (for
  prices, a return such as `log(close / prevClose)`; for other series, a
  difference or a deviation from baseline), i.e. a series fluctuating around ≈0. The rolling
  variance uses the running-sums form `Σx² − W·mean²`; that subtraction is exact
  when the mean is small relative to the spread, but loses precision the further
  the mean sits from zero. Concretely the relative error per coefficient is
  ≈ `eps · (1 + (mean/std)²)`: negligible for near-zero-mean data, but it degrades
  visibly if you feed **raw levels** or a near-constant series pinned on a large
  offset. (This trade is deliberate — the uncentered form is what makes the update
  `O(1)` per pair; difference your data first, as you would for the batch API.)
- **One timescale per engine.** Every `onBar` must carry values at the *same*
  sampling frequency; never mix frequencies (e.g. daily and intraday bars) into one
  engine. Combine timescales downstream by blending two engines' matrices, not by
  interleaving ticks.
- **Non-finite values are tolerated, not required.** Cleaning raw data with the
  `prep` package first is still recommended, but a `NaN`/`Infinity` in a bar is
  accepted: it makes that variable **undefined** — its whole row/column, diagonal
  included, reads `NaN` — for as long as the value stays in the window, then the
  variable is defined again, matching a fresh batch recompute, once the window is
  finite again. A variable that is **constant over the window** has zero variance
  and is reported the same way (whole row/column `NaN`).
- **Window ≥ 2**, and `returns.length` must equal the variable count every bar.
- **Not thread-safe.** A single engine instance is single-writer; drive it from
  one ingest thread.

## Package structure

```
ch.tarvynanalytics.corrcalc.lib/
├── matrix/       # DoubleMatrix & FloatMatrix — flat column-major storage
├── correlation/  # CorrelationCalculator, CorrelationType (Pearson, partial, Spearman, Kendall), Correlations factory
├── prep/         # DataPreparer steps: dropMissingRows, imputeMean, center, standardize
├── io/           # MatrixReader, CsvMatrixReader (whitespace-separated values)
├── stream/       # RollingCorrelationEngine — incremental rolling-window Pearson over a live bar stream
└── exception/    # CorrCalcException, InvalidInputException
```

## Design notes

- **Column-major flat storage.** All statistics here are per-column (means,
  variances, column dot products), so each column being one contiguous memory
  block keeps the hot loops sequential and cache-friendly. `DoubleMatrix.columnMajor`
  and `DoubleMatrix.data()` are zero-copy by design.
- **Pearson in two passes.** Each column is centered and scaled by the inverse
  of its centered norm; every coefficient is then a single dot product — the
  `(n-1)` factors cancel out exactly. Extra memory: one `n*p` working buffer
  plus the `p*p` result.
- **Parallelism adjusts to the machine.** Both phases fan out across columns on
  the ForkJoin common pool (sized to the available cores) once the estimated
  work crosses a threshold; small inputs stay on the calling thread.
- **Partial correlation via the precision matrix.** `Correlations.partial()`
  computes the Pearson matrix `R`, inverts it to the precision matrix
  `P = R⁻¹` (Cholesky, since `R` is symmetric positive-definite), and reads off
  `ρ_ij = −P_ij/√(P_ii·P_jj)`. It reuses every Pearson profile for the only
  `O(n·p²)` work; the `O(p³)` inversion is always double precision (even for
  float input) and negligible while `n ≫ p`. A constant column yields `NaN`
  (as in Pearson); a singular `R` — collinear columns or fewer observations
  than variables — has no precision matrix and is rejected with
  `InvalidInputException`.
- **Spearman by ranking, then Pearson.** `Correlations.spearman()` replaces each
  column with its average ranks (ties share their mean rank) and runs Pearson on
  the ranks, so it measures monotonic rather than linear association and reuses
  every profile and the precision contract unchanged. Ranking is an
  `O(n·log n)` per-column merge sort over an `int` index array (no boxing); a
  constant column has zero rank variance and so yields `NaN`, like Pearson.
- **Kendall (tau-b) by inversion counting.** `Correlations.kendall()` measures
  ordinal association from concordant vs discordant pairs. Rather than the naive
  `O(n²)` pair enumeration, it uses Knight's method: sort each pair of columns by
  `(x, y)` and count discordances as `y`-inversions while merge-sorting — so each
  coefficient is `O(n·log n)` and the whole matrix `O(n·log n·p²)`. Counts are
  exact `long`s (the final ratio is formed in `double`), so it is purely ordinal
  and identical on every profile; a constant column ties every pair and yields
  `NaN`. Being quadratic in `p`, it is best suited to tall, not wide, matrices.
- **Selectable calculation profiles.** `Correlations.pearson(Profile...)` picks
  the implementation strategy. Every profile computes the same statistic and
  passes the same test suite; they differ in inner-loop execution and JVM
  requirements. `STANDARD` (the default) is the portable scalar baseline;
  `HIGH_PERFORMANCE` uses 4x4 register-blocked tiles (up to ~2.6x double /
  ~1.7x float on large inputs, same accuracy class, no JVM flags);
  `VECTORIZED` adds explicit SIMD+FMA via the incubator Vector API and
  **requires a Java 25+ JVM started with
  `--add-modules jdk.incubator.vector`** — requesting it without that fails
  fast, never falls back silently.
- **Single-precision variant with a precision contract.** `FloatMatrix` halves
  memory and data transfer. Sums always accumulate at least as precisely as
  the result type: `calculate(floatMatrix)` returns a `FloatMatrix` and may
  use chunked float accumulation where the profile supports it (the error
  stays below the float result's own rounding step), while
  `calculateToDouble(floatMatrix)` returns an unrounded `DoubleMatrix` from
  pure double accumulation — the most accurate result float input can
  support, without doubling the big `n x p` allocation.
- **Missing values are explicit.** Calculators expect clean input; NaN handling
  is the job of the `prep` package (listwise deletion or mean imputation).
- **Zero-variance columns** yield `NaN` coefficients (the value is undefined),
  with the diagonal staying `1`.

## Benchmarks

JMH benchmarks live in [`corrcalc-lib-bench/`](corrcalc-lib-bench/) (a
reactor module that is never deployed — the published library pom stays
zero-dependency). All official numbers are measured
on the project's reference machine; raw JMH JSON for every official run is
versioned under [`corrcalc-lib-bench/results/`](corrcalc-lib-bench/results/). CI never produces
published numbers (shared runners are too noisy); a manual-dispatch
[workflow](.github/workflows/benchmark.yml) exists for sanity checks only.

### Latest release

<!-- benchmark-release:start -->
Release **v1.2.0** (`805b56c`), measured on 2026-06-13 with JDK 25.0.1 on Intel Core i7-6820HQ (8 threads, Windows host). JMH average time per correlation matrix in **ms/op** (± 99.9% confidence interval) and heap allocated per calculation in **MB/op** (`gc.alloc.rate.norm`); lower is better.

#### Pearson correlation

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.08 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 9.18 ± 0.46 | 10.34 ± 0.15 | 8.09 | 4.05 |
| 100,000 × 100 | 202.04 ± 5.57 | 113.23 ± 4.27 | 80.09 | 40.05 |
| 10,000 × 1,000 | 1,316.34 ± 109.14 | 1,010.65 ± 76.93 | 88.03 | 44.03 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.07 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 6.48 ± 0.03 | 7.39 ± 0.49 | 8.09 | 4.05 |
| 100,000 × 100 | 89.30 ± 1.77 | 77.00 ± 3.39 | 80.09 | 40.05 |
| 10,000 × 1,000 | 507.19 ± 15.89 | 613.94 ± 40.07 | 88.04 | 44.04 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.05 ± 0.00 | 0.06 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 3.11 ± 0.19 | 1.57 ± 0.04 | 8.09 | 4.05 |
| 100,000 × 100 | 85.21 ± 1.44 | 37.07 ± 0.54 | 80.09 | 40.05 |
| 10,000 × 1,000 | 339.14 ± 21.30 | 135.82 ± 4.67 | 88.04 | 44.04 |

#### Partial correlation

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.09 ± 0.00 | 0.08 | 0.05 |
| 10,000 × 100 | 10.17 ± 0.14 | 11.02 ± 0.09 | 8.41 | 4.45 |
| 100,000 × 100 | 226.12 ± 9.21 | 113.62 ± 4.55 | 80.41 | 40.45 |
| 10,000 × 1,000 | 2,172.31 ± 128.14 | 1,741.16 ± 190.47 | 120.04 | 84.03 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.08 ± 0.00 | 0.08 | 0.05 |
| 10,000 × 100 | 7.42 ± 0.52 | 8.02 ± 0.05 | 8.41 | 4.45 |
| 100,000 × 100 | 98.80 ± 2.91 | 77.52 ± 1.17 | 80.41 | 40.45 |
| 10,000 × 1,000 | 1,283.42 ± 48.05 | 1,328.12 ± 128.70 | 120.04 | 84.04 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.05 ± 0.00 | 0.06 ± 0.00 | 0.08 | 0.05 |
| 10,000 × 100 | 3.88 ± 0.23 | 2.95 ± 0.05 | 8.41 | 4.45 |
| 100,000 × 100 | 86.92 ± 3.46 | 37.90 ± 1.36 | 80.41 | 40.45 |
| 10,000 × 1,000 | 1,066.67 ± 49.52 | 898.43 ± 42.63 | 120.04 | 84.04 |

#### Spearman correlation

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 1.08 ± 0.01 | 1.14 ± 0.20 | 0.24 | 0.16 |
| 10,000 × 100 | 136.02 ± 3.41 | 141.37 ± 13.39 | 24.09 | 16.05 |
| 100,000 × 100 | 1,897.30 ± 37.82 | 1,738.19 ± 31.36 | 240.10 | 160.06 |
| 10,000 × 1,000 | 2,570.11 ± 83.42 | 2,305.86 ± 289.68 | 248.09 | 164.09 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 1.07 ± 0.02 | 1.09 ± 0.03 | 0.24 | 0.16 |
| 10,000 × 100 | 133.25 ± 4.96 | 137.08 ± 4.39 | 24.09 | 16.05 |
| 100,000 × 100 | 1,787.75 ± 89.38 | 1,698.09 ± 33.43 | 240.10 | 160.06 |
| 10,000 × 1,000 | 1,814.54 ± 51.60 | 1,827.85 ± 47.23 | 248.10 | 164.10 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 1.07 ± 0.05 | 1.06 ± 0.03 | 0.24 | 0.16 |
| 10,000 × 100 | 137.44 ± 25.28 | 130.22 ± 5.80 | 24.09 | 16.05 |
| 100,000 × 100 | 1,771.24 ± 21.39 | 1,666.47 ± 87.11 | 240.10 | 160.06 |
| 10,000 × 1,000 | 1,597.05 ± 60.30 | 1,370.22 ± 13.65 | 248.10 | 164.10 |

#### Kendall correlation

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 3.65 ± 0.16 | 3.49 ± 0.05 | 0.09 | 0.09 |
| 10,000 × 100 | 3,296.48 ± 822.58 | 3,399.76 ± 283.97 | 8.17 | 8.09 |
| 100,000 × 100 | 41,070.19 ± 4,582.32 | 43,291.04 ± 3,935.42 | 80.89 | 80.45 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 3.53 ± 0.12 | 3.51 ± 0.05 | 0.09 | 0.09 |
| 10,000 × 100 | 3,145.95 ± 366.55 | 3,459.77 ± 284.27 | 8.17 | 8.09 |
| 100,000 × 100 | 41,074.31 ± 6,652.63 | 43,031.59 ± 6,269.54 | 80.89 | 80.45 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 3.53 ± 0.05 | 3.50 ± 0.08 | 0.09 | 0.09 |
| 10,000 × 100 | 3,064.44 ± 205.23 | 3,406.78 ± 124.68 | 8.17 | 8.09 |
| 100,000 × 100 | 40,753.86 ± 5,516.99 | 42,754.63 ± 3,756.18 | 80.89 | 80.45 |
<!-- benchmark-release:end -->

### Streaming engine

The `stream` package ingests **one bar at a time** rather than a whole matrix, so its
benchmark unit is a single `onBar` call. Measured on the reference machine (Intel Core
i7-6820HQ, 8 threads, Windows host, JDK 25.0.1, 2026-06-23): **throughput in bars/second**
(higher is better) and **heap allocated per bar** (`gc.alloc.rate.norm`), window `W = 480`:

| variables `N` | incremental slide | slide + snapshot every bar | naive: recompute matrix every bar | slide vs. recompute |
|---|---|---|---|---|
| 16  | 2,910,000 bars/s · **0 B** | 736,000 bars/s · 2 KB  | 17,400 bars/s | **≈167×** |
| 50  | 475,000 bars/s · **0 B**   | 99,000 bars/s · 20 KB  | 3,790 bars/s  | **≈125×** |
| 100 | 140,000 bars/s · **0 B**   | 27,500 bars/s · 80 KB  | 1,570 bars/s  | **≈89×**  |

- **The incremental slide is allocation-free and independent of the window width `W`.** The
  rank-one update touches each of the `N(N−1)/2` pairs once, so it is `O(N²)` and does *not* depend
  on `W`: at `N = 16` it runs ~2.9M bars/s whether `W = 120` or `W = 480`. Recomputing the matrix
  each bar is `O(W·N²)`, so it costs far more *and grows with `W`* (`N = 16`: 60k bars/s at
  `W = 120` → 17k at `W = 480`). That gap — **≈90–170× at `W = 480`** — is the whole point of an
  online engine: you pay `O(N²)` per bar instead of `O(W·N²)`.
- **The snapshot is the only allocation.** Emitting the full `N×N` matrix every bar allocates
  exactly `N²·8` bytes (2 KB at `N = 16`, 80 KB at `N = 100`); the slide itself allocates nothing.
  For wide, high-rate streams, raise `emitEveryNUpdates` so snapshots are emitted on a coarser
  cadence than every bar.
- **Single core.** The engine is single-writer, so these are single-thread numbers; at hundreds
  of variables the `O(N²)` slide is the cap.

### Choosing a profile

`HIGH_PERFORMANCE` and `VECTORIZED` only accelerate the **Pearson correlation
kernel** (the register-blocked / SIMD+FMA dot products). How much they help
therefore depends on how large a share of your statistic's runtime actually
sits in that kernel — which changes sharply with the correlation type and the
matrix shape. Approximate `STANDARD → VECTORIZED` speedup (double precision, on
the reference machine):

| rows × cols | Pearson | Partial | Spearman | Kendall |
|---|---|---|---|---|
| 10,000 × 100 | ~3.0× | ~2.6× | ~1.0× | 1× |
| 100,000 × 100 | ~2.4× | ~2.6× | ~1.1× | 1× |
| 10,000 × 1,000 | ~3.9× | ~2.0× | ~1.6× | — |

- **Pearson** scales best: almost all the work is the kernel, and the gain
  grows with the variable count `p` as the `O(n·p²)` dot-product phase comes to
  dominate.
- **Partial** adds a scalar, un-vectorized `O(p³)` matrix inversion. It tracks
  Pearson while `p` is small, but the inversion erodes the advantage as `p`
  grows (≈2× at `p = 1,000`).
- **Spearman** is bounded by the un-vectorized per-column ranking, which the
  profiles never touch — so the profile barely moves the needle until `p` is
  large enough that the Pearson phase resurfaces.
- **Kendall** does not use the Pearson kernel at all (it is sort-and-count
  based), so it is **profile-independent** — every profile runs the same code
  (and Kendall never needs the Vector API module). Note its cost is quadratic in
  `p`, so it is impractical for very wide matrices (hence the `—` above).

Rule of thumb: reach for `VECTORIZED` (or `HIGH_PERFORMANCE` when the Vector API
is unavailable) for **Pearson** and **partial** correlation, the more so with
many variables; for the rank correlations the choice barely matters — **Spearman**
is ranking-bound and **Kendall** ignores the profile entirely, so `STANDARD` is
as good as any.

### Versus other pure-Java libraries

How corrcalc compares to the best-performing pure-Java alternatives for a full
Pearson correlation matrix (double precision, same seeded Gaussian data, on the
reference machine; **ms/op**, lower is better):

| rows × cols | corrcalc `STANDARD` | corrcalc `VECTORIZED` | ojAlgo | EJML | Commons Math |
|---|---|---|---|---|---|
| 1,000 × 10 | 0.05 | 0.04 | 0.33 | 0.11 | 0.58 |
| 10,000 × 100 | 9.1 | 3.1 | 29.8 | 196.8 | 741.3 |
| 100,000 × 100 | 296.9 | 151.2 | 399.5 | 8,245 | 15,566 |
| 10,000 × 1,000 | 1,424.9 | 432.1 | 2,135 | 59,831 | 78,236 |

corrcalc is fastest at every size — even the portable `STANDARD` profile beats
every rival — and `VECTORIZED` is roughly **2.6–10× faster than the best rival
(ojAlgo)** and **100–240× faster than Apache Commons Math**. The contenders:

- **ojAlgo** — multi-threaded pure-Java matmul (`transpose().multiply()`); the
  closest competitor, since it also parallelizes. corrcalc still wins through
  the column-major, register-blocked and SIMD kernels purpose-built for the
  tall-skinny correlation shape.
- **EJML** — `CommonOps_DDRM.multInner`, single-threaded; competitive only on
  tiny inputs.
- **Apache Commons Math** — `PearsonsCorrelation`, the conventional choice;
  single-threaded and pair-at-a-time, so it trails badly at scale.

EJML and ojAlgo have no built-in correlation, so each got the idiomatic route a
user would write (centre the columns, form `Xᶜᵀ·Xᶜ` with the library's matmul,
normalize by the diagonal); Commons Math has a direct API. All produce the same
matrix. These are quick-check numbers (single fork, short iterations) — the gaps
are orders of magnitude so the ranking is unambiguous, but treat the absolute
values as indicative. The rival libraries are **bench-scope dependencies only**;
the published library stays zero-dependency.

### Running them yourself

```bash
./mvnw -DskipTests clean package
java -jar corrcalc-lib-bench/target/benchmarks.jar -p profile=STANDARD,HIGH_PERFORMANCE,VECTORIZED   # all profiles, ~40 min
java -jar corrcalc-lib-bench/target/benchmarks.jar -p size=10000x100 -f 1 -wi 2 -i 3   # quick check (default profile)
java -jar corrcalc-lib-bench/target/benchmarks.jar RollingCorrelationBenchmark -prof gc   # streaming engine: bars/s + bytes/bar
```

If you develop inside WSL, build there but run the jar on the Windows host
(`cmd.exe /c "java -jar corrcalc-lib-bench\target\benchmarks.jar"`): the WSL2
VM typically gets only half the logical CPUs plus virtualization overhead,
which skews results by 40%+ and hits the float variant hardest.

Add `-prof gc` to also measure memory: `gc.alloc.rate.norm` reports bytes
allocated per calculation and should match the documented working-set budget
(one `n·p` buffer plus the `p·p` result).

Performance claims are only ever proven by same-machine, same-session A/B
runs: benchmark the base branch and the change back to back — absolute
numbers across machines or days are not comparable.

## Contributing

Step-by-step guides for extending the library (new correlation types, data
preparation steps, storage types) and the project's invariants and testing
conventions live in [CLAUDE.md](CLAUDE.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
