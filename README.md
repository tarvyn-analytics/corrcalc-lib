# CorrCalc Lib

A pure Java library for calculating correlation matrices from numerical datasets.
Zero runtime dependencies, built for speed and low memory overhead.

**Tech stack:** Java 21+ at runtime (the optional `VECTORIZED` profile needs
25+), JDK 25 to build, Maven, JUnit 5, JaCoCo (80% line / 70% branch minimum,
enforced by the build).

## Quick start

```bash
./mvnw clean verify     # build, run all tests, enforce coverage
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
```

## Package structure

```
ch.tarvynanalytics.corrcalc.lib/
├── matrix/       # DoubleMatrix & FloatMatrix — flat column-major storage
├── correlation/  # CorrelationCalculator, CorrelationType (Pearson, partial, Spearman), Correlations factory
├── prep/         # DataPreparer steps: dropMissingRows, imputeMean, center, standardize
├── io/           # MatrixReader, CsvMatrixReader (whitespace-separated values)
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
Release **v1.1.0** (`2907d76`), measured on 2026-06-13 with JDK 25.0.1 on Intel Core i7-6820HQ (8 threads, Windows host). JMH average time per correlation matrix in **ms/op** (± 99.9% confidence interval) and heap allocated per calculation in **MB/op** (`gc.alloc.rate.norm`); lower is better.

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.01 | 0.08 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 9.54 ± 0.67 | 10.41 ± 0.19 | 8.09 | 4.05 |
| 100,000 × 100 | 227.45 ± 19.17 | 123.29 ± 6.78 | 80.09 | 40.05 |
| 10,000 × 1,000 | 1,432.24 ± 44.35 | 1,120.25 ± 156.43 | 88.03 | 44.03 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.01 | 0.08 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 6.68 ± 0.13 | 7.44 ± 0.42 | 8.09 | 4.05 |
| 100,000 × 100 | 99.69 ± 7.39 | 81.65 ± 0.99 | 80.09 | 40.05 |
| 10,000 × 1,000 | 550.52 ± 26.16 | 644.96 ± 38.30 | 88.04 | 44.04 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.05 ± 0.01 | 0.06 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 3.29 ± 0.17 | 1.63 ± 0.02 | 8.09 | 4.05 |
| 100,000 × 100 | 96.26 ± 4.82 | 41.35 ± 0.52 | 80.09 | 40.05 |
| 10,000 × 1,000 | 376.83 ± 8.51 | 151.37 ± 4.34 | 88.04 | 44.04 |
<!-- benchmark-release:end -->

### Development snapshot

<!-- benchmark-snapshot:start -->
Development snapshot **v1.1.1-SNAPSHOT** (`c7a0f44`), measured on 2026-06-13 with JDK 25.0.1 on Intel Core i7-6820HQ (8 threads, Windows host). JMH average time per correlation matrix in **ms/op** (± 99.9% confidence interval) and heap allocated per calculation in **MB/op** (`gc.alloc.rate.norm`); lower is better.

#### Pearson correlation

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.09 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 9.58 ± 0.27 | 11.31 ± 4.59 | 8.09 | 4.05 |
| 100,000 × 100 | 225.12 ± 9.97 | 113.76 ± 4.08 | 80.09 | 40.05 |
| 10,000 × 1,000 | 1,360.20 ± 254.17 | 1,040.82 ± 118.66 | 88.03 | 44.03 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.07 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 6.56 ± 0.35 | 7.35 ± 0.12 | 8.09 | 4.05 |
| 100,000 × 100 | 88.37 ± 1.51 | 76.02 ± 1.17 | 80.09 | 40.05 |
| 10,000 × 1,000 | 515.13 ± 8.69 | 625.66 ± 61.68 | 88.04 | 44.04 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.05 ± 0.00 | 0.06 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 2.99 ± 0.05 | 1.62 ± 0.05 | 8.09 | 4.05 |
| 100,000 × 100 | 85.35 ± 2.08 | 39.87 ± 1.31 | 80.09 | 40.05 |
| 10,000 × 1,000 | 352.87 ± 83.14 | 149.96 ± 12.83 | 88.04 | 44.04 |

#### Partial correlation

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.01 | 0.09 ± 0.00 | 0.08 | 0.05 |
| 10,000 × 100 | 10.80 ± 0.27 | 11.18 ± 0.54 | 8.41 | 4.45 |
| 100,000 × 100 | 259.50 ± 43.83 | 123.92 ± 4.18 | 80.41 | 40.45 |
| 10,000 × 1,000 | 2,361.76 ± 248.17 | 1,855.47 ± 70.42 | 120.04 | 84.03 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.08 ± 0.00 | 0.08 | 0.05 |
| 10,000 × 100 | 7.90 ± 0.56 | 8.08 ± 0.19 | 8.41 | 4.45 |
| 100,000 × 100 | 107.70 ± 18.66 | 82.51 ± 2.66 | 80.41 | 40.45 |
| 10,000 × 1,000 | 1,292.64 ± 42.97 | 1,360.35 ± 94.43 | 120.04 | 84.04 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.05 ± 0.00 | 0.06 ± 0.00 | 0.08 | 0.05 |
| 10,000 × 100 | 3.93 ± 0.09 | 2.89 ± 0.03 | 8.41 | 4.45 |
| 100,000 × 100 | 97.46 ± 4.01 | 41.83 ± 0.81 | 80.41 | 40.45 |
| 10,000 × 1,000 | 1,106.62 ± 96.30 | 925.41 ± 60.08 | 120.04 | 84.04 |

#### Spearman correlation

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 1.11 ± 0.02 | 1.09 ± 0.08 | 0.24 | 0.16 |
| 10,000 × 100 | 141.02 ± 1.71 | 142.25 ± 1.28 | 24.09 | 16.05 |
| 100,000 × 100 | 1,962.95 ± 51.95 | 1,737.98 ± 40.77 | 240.10 | 160.06 |
| 10,000 × 1,000 | 2,692.63 ± 276.79 | 2,272.26 ± 140.07 | 248.09 | 164.09 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 1.07 ± 0.03 | 1.09 ± 0.02 | 0.24 | 0.16 |
| 10,000 × 100 | 133.37 ± 4.70 | 137.08 ± 6.56 | 24.09 | 16.05 |
| 100,000 × 100 | 1,772.79 ± 21.86 | 1,687.07 ± 21.62 | 240.10 | 160.06 |
| 10,000 × 1,000 | 1,780.16 ± 48.43 | 1,846.13 ± 42.29 | 248.10 | 164.10 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 1.06 ± 0.03 | 1.07 ± 0.02 | 0.24 | 0.16 |
| 10,000 × 100 | 131.01 ± 1.36 | 133.40 ± 3.07 | 24.09 | 16.05 |
| 100,000 × 100 | 1,778.50 ± 20.91 | 1,642.64 ± 32.24 | 240.10 | 160.06 |
| 10,000 × 1,000 | 1,648.89 ± 132.31 | 1,369.72 ± 33.50 | 248.10 | 164.10 |
<!-- benchmark-snapshot:end -->

### Choosing a profile

`HIGH_PERFORMANCE` and `VECTORIZED` only accelerate the **Pearson correlation
kernel** (the register-blocked / SIMD+FMA dot products). How much they help
therefore depends on how large a share of your statistic's runtime actually
sits in that kernel — which changes sharply with the correlation type and the
matrix shape. Approximate `STANDARD → VECTORIZED` speedup (double precision, on
the reference machine):

| rows × cols | Pearson | Partial | Spearman |
|---|---|---|---|
| 10,000 × 100 | ~3.2× | ~2.7× | ~1.1× |
| 100,000 × 100 | ~2.6× | ~2.7× | ~1.1× |
| 10,000 × 1,000 | ~3.9× | ~2.1× | ~1.6× |

- **Pearson** scales best: almost all the work is the kernel, and the gain
  grows with the variable count `p` as the `O(n·p²)` dot-product phase comes to
  dominate.
- **Partial** adds a scalar, un-vectorized `O(p³)` matrix inversion. It tracks
  Pearson while `p` is small, but the inversion erodes the advantage as `p`
  grows (≈2× at `p = 1,000`).
- **Spearman** is bounded by the un-vectorized per-column ranking, which the
  profiles never touch — so the profile barely moves the needle until `p` is
  large enough that the Pearson phase resurfaces.

Rule of thumb: reach for `VECTORIZED` (or `HIGH_PERFORMANCE` when the Vector API
is unavailable) for **Pearson** and **partial** correlation, the more so with
many variables; for **Spearman**, `STANDARD` is essentially as fast, since
ranking — not the correlation kernel — is the bottleneck.

### Running them yourself

```bash
./mvnw -DskipTests clean package
java -jar corrcalc-lib-bench/target/benchmarks.jar -p profile=STANDARD,HIGH_PERFORMANCE,VECTORIZED   # all profiles, ~40 min
java -jar corrcalc-lib-bench/target/benchmarks.jar -p size=10000x100 -f 1 -wi 2 -i 3   # quick check (default profile)
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
