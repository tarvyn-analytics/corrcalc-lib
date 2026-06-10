# CorrCalc Lib

A pure Java library for calculating correlation matrices from numerical datasets.
Zero runtime dependencies, built for speed and low memory overhead.

**Tech stack:** Java 21+, Maven, JUnit 5, JaCoCo (80% line / 70% branch minimum, enforced by the build).

## Quick start

```bash
./mvnw clean verify     # build, run all tests, enforce coverage
```

```java
import ch.corrcalc.lib.correlation.Correlations;
import ch.corrcalc.lib.io.CsvMatrixReader;
import ch.corrcalc.lib.matrix.DoubleMatrix;
import ch.corrcalc.lib.matrix.Matrix;
import ch.corrcalc.lib.prep.Preparers;

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

        // 3) Calculate the correlation matrix (p x p, symmetric, ones on the diagonal)
        DoubleMatrix corr = Correlations.pearson().calculate(prepared);
        double r01 = corr.get(0, 1);
```

## Package structure

```
ch.corrcalc.lib/
├── matrix/       # Matrix (double) & FloatMatrix (float) — flat column-major storage
├── correlation/  # CorrelationCalculator, CorrelationType, Correlations factory
├── prep/         # DataPreparer steps: dropMissingRows, imputeMean, center, standardize
├── io/           # MatrixReader, CsvMatrixReader (whitespace-separated values)
└── exception/    # CorrCalcException, InvalidInputException
```

## Design notes

- **Column-major flat storage.** All statistics here are per-column (means,
  variances, column dot products), so each column being one contiguous memory
  block keeps the hot loops sequential and cache-friendly. `Matrix.columnMajor`
  and `Matrix.data()` are zero-copy by design.
- **Pearson in two passes.** Each column is centered and scaled by the inverse
  of its centered norm; every coefficient is then a single dot product — the
  `(n-1)` factors cancel out exactly. Extra memory: one `n*p` working buffer
  plus the `p*p` result.
- **Parallelism adjusts to the machine.** Both phases fan out across columns on
  the ForkJoin common pool (sized to the available cores) once the estimated
  work crosses a threshold; small inputs stay on the calling thread.
- **Single-precision variant.** `FloatMatrix` halves memory and data transfer;
  `Correlations.pearson().calculate(floatMatrix)` returns a `FloatMatrix` while
  all sums still accumulate in double precision.
- **Missing values are explicit.** Calculators expect clean input; NaN handling
  is the job of the `prep` package (listwise deletion or mean imputation).
- **Zero-variance columns** yield `NaN` coefficients (the value is undefined),
  with the diagonal staying `1`.

## Extending

New correlation types (partial correlation is the next candidate) plug in as:

1. a new constant in `CorrelationType`,
2. a package-private implementation of `CorrelationCalculator`,
3. a new arm in the `Correlations` factory.
