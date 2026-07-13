# CLAUDE.md

Guidance for Claude Code when working in this repository. Read `README.md` first — it owns
the overview, public API, package layout and design rationale.

## Commands

```bash
./mvnw clean verify                       # full build: tests + coverage gates — run before claiming done
./mvnw test -Dtest=ClassName              # single test class
./mvnw -Ppublish -DskipTests javadoc:jar  # CI's javadoc gate — plain verify never runs it
```

Multi-module reactor: `corrcalc-lib-core` (the published library) + `corrcalc-lib-bench`
(JMH, never deployed). **Building requires JDK 25** (the `Vectorized*` kernels compile
`--release 25`); the jar runs on Java 21+. This is a library — the tests are the executable
spec. JMH benchmarks: build in WSL, **run on the Windows host** via `cmd.exe` interop (the
WSL2 VM skews timings 40%+); only same-session A/B deltas are valid; official numbers come
only from the reference machine (history in `corrcalc-lib-bench/results/`; per-op allocation
must stay at the documented `(n·p + p·p) × element size` working-set budget).

## Invariants — never break these

1. **Zero runtime dependencies.** Test scope (JUnit) is the only exception.
2. **Column-major flat arrays.** Element `(row, col)` lives at `col * rows + row`.
3. **Zero-copy contracts.** `columnMajor(...)` takes ownership; `data()` returns the live
   backing array; never add defensive copies to these paths.
4. **Accumulate at least as precisely as the result type.** Double results — including
   `calculateToDouble(FloatMatrix)` — always come from pure double accumulation. Float→float
   kernels may fold bounded chunks (1024-row float blocks into double) only with adversarial
   accuracy tests proving the error bound (COR-324: max 8e-9 vs ~6e-8 float ulp at |r|=1).
5. **Calculators and preparers are stateless**; factories (`Correlations`, `Preparers`) hand
   out lazy shared singletons; preparers return a new matrix, never mutate input. A profile
   with unmet JVM requirements fails fast on first request, never silently falls back.
6. **Implementations are package-private.** Public surface = interfaces, factories, matrix
   types, exceptions.
7. **Validation throws `InvalidInputException`** with offending values in `[brackets]`.
8. **Coverage gates 80% line / 70% branch** enforced by `verify` (actual ~99%); new code
   arrives with tests in the same commit.

New code mirrors the existing precedent — find the sibling calculator/profile/preparer/matrix
type and copy its structure and test suite (the calculator suites are `@EnumSource(Profile.class)`
parametrized, so new profiles inherit the full oracle). The reference-oracle tests are the
safety net for any hot-loop change; parallel tasks may only write disjoint index sets.

## Testing conventions

Naming `method_Scenario_Expectation`; test packages mirror main 1:1; assertions
`(expected, actual)` (one documented exception in `FloatMatrixTest`). Numerical correctness
is proven against independent naive implementations on seeded data — never against the code's
own output.

## Delivery

Jira **COR** (`.claude/tools/jira/jira.sh`; usage in `.claude/skills/jira/SKILL.md`). GitFlow:
PR-only **squash** into `develop`, branch `feature/COR-<n>-eb-<desc>`; releases `develop`→`main`
as a **true merge commit**, then back-merge and bump `-SNAPSHOT` (procedure:
`.claude/skills/release/SKILL.md`). Conventional commits with issue key; `--no-gpg-sign` under
WSL. CI: PR validation runs `-Ppublish clean verify` (javadoc errors fail there); pushes
publish to GitHub Packages (develop=SNAPSHOT, main=release+`vX.Y.Z` tag — bump before the
release merge; the same version can't publish twice). `benchmark.yml` is manual-dispatch
sanity only — its numbers are never published. No DB, no Docker.
