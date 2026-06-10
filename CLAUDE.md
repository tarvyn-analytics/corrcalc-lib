# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

CorrCalc Lib is a pure Java library for calculating correlation matrices from
numerical datasets. **Zero runtime dependencies** — no Spring, no database, no
web layer; keep it that way. Performance (speed, memory, minimal data transfer)
is the primary design goal.

**Tech stack:** Java 21+ (built with `maven.compiler.release=21`), Maven,
JUnit 5, JaCoCo.

## Common Commands

```bash
./mvnw clean verify          # build, run all tests, enforce coverage gates
./mvnw test                  # tests only
./mvnw test -Dtest=ClassName # single test class
```

There is nothing to "run" — this is a library; the tests are the executable spec.

## Architecture

```
ch.corrcalc.lib/
├── matrix/       # Matrix (double) & FloatMatrix (float) — flat column-major
│                 # storage; AbstractMatrix holds the shared shape/bounds logic
├── correlation/  # CorrelationCalculator, CorrelationType, Correlations factory;
│                 # implementations are package-private (PearsonCorrelationCalculator)
├── prep/         # DataPreparer steps behind the Preparers factory:
│                 # dropMissingRows, imputeMean, center, standardize
├── io/           # MatrixReader + CsvMatrixReader (whitespace-separated values,
│                 # "NaN" tokens = missing values)
└── exception/    # CorrCalcException (base), InvalidInputException
```

## Design Rules

- **Column-major flat arrays everywhere.** Element `(row, col)` lives at
  `col * rows + row`. All statistics are per-column, so columns must stay
  contiguous. `data()` exposes the live backing array on purpose (zero-copy);
  factory methods taking arrays take ownership without copying.
- **Accumulate in double, always** — also in the float code paths. Only loads
  and stores are single precision.
- **Parallelism via the ForkJoin common pool** (auto-sized to cores), gated by
  `PARALLEL_THRESHOLD_FLOPS` (~n*p*p) so small inputs stay on the calling
  thread. No CPU/GPU-specific tuning yet.
- **Calculators expect clean input.** NaN handling (drop/impute) belongs in
  `prep`, not in calculators. Zero-variance columns yield NaN coefficients
  with the diagonal staying 1.
- **Preparers never mutate their input** and are stateless; factories return
  shared singletons.
- **double/float duplication is confined to kernels.** The Pearson engine is
  written once, generic over the storage array; `Kernels<A>` implementations
  hold the type-specific inner loops. Follow this pattern for new calculators.

## Adding a Correlation Type (e.g. partial correlation)

1. Add a constant to `CorrelationType`.
2. Add a package-private implementation of `CorrelationCalculator`
   (both the `Matrix` and `FloatMatrix` methods).
3. Add the factory method and `switch` arm in `Correlations`.
4. Mirror the test layout: port edge cases, cross-check against a naive
   reference implementation in the test for both serial and parallel paths.

## Testing Conventions

- Test naming: `method_Scenario_Expectation` (e.g.
  `calculate_ZeroVarianceColumns_ReturnsNaNCorrelation`).
- Tests mirror the main package layout 1:1; cross-package flows go into
  `CorrelationEndToEndTest` at the root.
- Numerical results are verified against independent naive textbook
  implementations written inside the tests, on seeded random data.
- Coverage gates (enforced by `verify`): **80% line, 70% branch** minimum.

## Pitfalls

- Git commit GPG signing fails under WSL ("Unusable secret key") — commit with
  `--no-gpg-sign` from WSL, or sign from Windows.
- The parallel code path only triggers when `n*p*p >= 2^18` — performance test
  matrices must be at least that large to exercise it.
