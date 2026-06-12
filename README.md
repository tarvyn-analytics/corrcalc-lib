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
```

## Package structure

```
ch.tarvynanalytics.corrcalc.lib/
├── matrix/       # DoubleMatrix & FloatMatrix — flat column-major storage
├── correlation/  # CorrelationCalculator, CorrelationType, Correlations factory
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
- **Single-precision variant.** `FloatMatrix` halves memory and data transfer;
  `Correlations.pearson().calculate(floatMatrix)` returns a `FloatMatrix` while
  all sums still accumulate in double precision.
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
_No released version benchmarked yet — this table is filled as part of the
release procedure._
<!-- benchmark-release:end -->

### Development snapshot

<!-- benchmark-snapshot:start -->
Development snapshot **v1.0.2-SNAPSHOT** (`6ecae55`), measured on 2026-06-12 with JDK 25.0.1 on Intel Core i7-6820HQ (8 threads, Windows host). JMH average time per correlation matrix in **ms/op** (± 99.9% confidence interval) and heap allocated per calculation in **MB/op** (`gc.alloc.rate.norm`); lower is better.

**`STANDARD`** (default)

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.01 | 0.08 ± 0.01 | 0.08 | 0.04 |
| 10,000 × 100 | 9.61 ± 2.39 | 10.33 ± 0.17 | 8.09 | 4.05 |
| 100,000 × 100 | 204.79 ± 12.61 | 113.25 ± 2.15 | 80.09 | 40.05 |
| 10,000 × 1,000 | 1,309.17 ± 74.03 | 1,042.79 ± 109.67 | 88.03 | 44.03 |

**`HIGH_PERFORMANCE`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.06 ± 0.00 | 0.07 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 6.46 ± 0.08 | 7.30 ± 0.05 | 8.09 | 4.05 |
| 100,000 × 100 | 87.36 ± 2.58 | 77.17 ± 5.16 | 80.09 | 40.05 |
| 10,000 × 1,000 | 510.93 ± 35.12 | 617.95 ± 54.88 | 88.04 | 44.04 |

**`VECTORIZED`**

| rows × cols | double | float | double alloc | float alloc |
|---|---|---|---|---|
| 1,000 × 10 | 0.04 ± 0.00 | 0.06 ± 0.00 | 0.08 | 0.04 |
| 10,000 × 100 | 3.12 ± 0.06 | 2.12 ± 0.01 | 8.09 | 4.05 |
| 100,000 × 100 | 85.29 ± 3.06 | 37.86 ± 1.27 | 80.09 | 40.05 |
| 10,000 × 1,000 | 332.39 ± 12.92 | 161.53 ± 13.44 | 88.04 | 44.04 |
<!-- benchmark-snapshot:end -->

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
