# CLAUDE.md

Guidance for Claude Code when working in this repository.

Read `README.md` first — it owns the project overview, public API examples,
package layout and design rationale. This file does not repeat that; it tells
you how to work on the code: the rules that must hold, and step-by-step guides
for the common tasks.

## Commands

```bash
./mvnw clean verify          # full build: tests + coverage gates — run before claiming done
./mvnw test                  # tests only (faster iteration)
./mvnw test -Dtest=ClassName # single test class

# JMH benchmarks: build in WSL, but RUN on the Windows host via interop —
# the WSL2 VM only gets 4 of the host's 8 logical CPUs plus virtualization
# overhead (~40-50% slower, float up to 2x on bandwidth-bound sizes).
# Never mix WSL-run and host-run numbers in one comparison.
./mvnw -DskipTests clean package
cmd.exe /c "java -jar corrcalc-lib-bench\target\benchmarks.jar -prof gc"   # full run, ~10 min
cmd.exe /c "java -jar corrcalc-lib-bench\target\benchmarks.jar -p size=10000x100 -f 1 -wi 2 -i 3"  # quick check
```

Multi-module reactor: the root pom (`corrcalc-lib-parent`) aggregates
`corrcalc-lib-core/` (the published library) and `corrcalc-lib-bench/` (JMH,
never deployed); module directories equal their artifactIds. Version lives in
the parent; `versions:set` at the root moves all modules together.

Coverage report: `corrcalc-lib-core/target/site/jacoco/index.html` (CSV next to it for scripting).
This is a library — there is no application to run; the tests are the
executable spec.

## Invariants — never break these

1. **Zero runtime dependencies.** Test scope (JUnit) is the only exception.
   Do not add Spring, Lombok, EJML, commons-*, anything.
2. **Column-major flat arrays.** Element `(row, col)` lives at
   `col * rows + row`. Every algorithm iterates columns as contiguous blocks.
3. **Zero-copy contracts.** `columnMajor(...)` takes ownership of the array;
   `data()` returns the live backing array. Never add defensive copies to
   these paths; never mutate an array you received through `data()` of a
   matrix you don't own.
4. **Accumulate in double, always** — including all `float[]` code paths.
   Only loads and stores are single precision.
5. **Calculators and preparers are stateless**; factories (`Correlations`,
   `Preparers`) hand out shared singletons. Preparers return a new matrix and
   never mutate their input.
6. **Implementations are package-private.** Only interfaces, factories,
   matrix types and exceptions are public. Keep it that way.
7. **Validation errors throw `InvalidInputException`** with the offending
   values in brackets, e.g. `"... got [3x0]"`.
8. **Coverage gates 80% line / 70% branch** are enforced by `verify`; actual
   coverage is ~99%. New code arrives with tests in the same commit.

## Task guides

### Add a correlation type (e.g. partial correlation)

1. Add the constant to `CorrelationType`.
2. Create a package-private `XxxCorrelationCalculator implements
   CorrelationCalculator` in `correlation/`. Implement **both** overloads
   (`DoubleMatrix` and `FloatMatrix`). Write the algorithm once as a private
   generic engine over the storage array `A` and reuse `Kernels<A>`
   (`DoubleKernels` / `FloatKernels`); extend `Kernels` with new primitives if
   the algorithm needs loops the interface doesn't cover yet.
3. Gate parallelism the same way Pearson does: estimate the work in
   multiply-adds, compare against a `PARALLEL_THRESHOLD_FLOPS`-style constant,
   and fan out across columns with the `columns(p, parallel)` ternary helper
   pattern (no `stream = stream.parallel()` reassignment — IntelliJ flags it).
4. Add the factory method and `switch` arm in `Correlations` (singleton field,
   like `PEARSON`).
5. Tests (see conventions below): port the Pearson edge cases that apply
   (zero variance, single row/column, empty input throws), verify against a
   naive textbook implementation written inside the test on seeded random
   data — once below and once above the parallel threshold — and add float
   tests comparing against the double result with ~1e-5 tolerance.
6. Mention the new type in README's package-structure comment if it changes.

### Add a data preparation step

1. Create a package-private `XxxPreparer implements DataPreparer` in `prep/`.
   Start from `observations.copy()` (or build a fresh array, like
   `DropMissingRowsPreparer` does when rows change) and work column-wise.
2. Decide the degenerate-column policy explicitly and document it in the
   class javadoc (precedents: all-NaN column → throw in `MeanImputePreparer`;
   zero variance → all zeros in `StandardizePreparer`).
3. Register a singleton + factory method in `Preparers`.
4. Tests: the transformation itself, the edge cases from step 2, and always a
   `prepare_InputMatrix_IsNotModified` snapshot test.

### Add a matrix storage type

Extend `AbstractMatrix` (shared shape/bounds logic lives there), mirror the
`DoubleMatrix` API surface exactly, then add a `Kernels` implementation and a
`CorrelationCalculator` overload per calculator. Mirror `DoubleMatrixTest`
completely, including the cross-type `equals` checks in both directions.

### Touch a hot loop

The reference-oracle tests are the safety net — they must keep passing for
both the serial and parallel paths. If you change buffering or normalization,
re-derive the memory accounting in the class javadoc
(`PearsonCorrelationCalculator` documents the `NP + PP` budget) and keep the
concurrency argument valid: parallel tasks may only write disjoint index sets.
Benchmark the change: run the JMH suite (see Commands) on the base branch and
on yours in the same session — same machine, back to back — and put the
before/after numbers in the PR description. Cross-machine or cross-day
absolute numbers are meaningless; only same-session deltas count. If the
numbers moved, refresh the README development-snapshot table in the same PR:

```bash
cmd.exe /c "java -jar corrcalc-lib-bench\target\benchmarks.jar -prof gc -rf json -rff corrcalc-lib-bench\results\$(date +%F)-<version>.json"
python3 corrcalc-lib-bench/update_benchmark_readme.py corrcalc-lib-bench/results/<that-file>.json README.md \
  --section snapshot --version <version> --commit <short-sha> \
  --runner "Intel Core i7-6820HQ (8 threads, Windows host)"
```

`-prof gc` adds memory accounting: `gc.alloc.rate.norm` must stay at the
documented working-set budget of `(n·p + p·p) × element size` bytes per op
(double 10000x100 ≈ 8.1 MB/op, float half that). Timings are noisy;
allocation per op is deterministic — if it jumps, a hidden copy landed on
the hot path.

Commit the results JSON together with the README — `corrcalc-lib-bench/results/` is the
benchmark history of the reference environment: the JMH jar always runs on
the Windows host (`cmd.exe` interop from WSL), never inside the WSL VM. If
the hardware or OS ever changes, history restarts and the `--runner` string
changes.

## Testing conventions

- Naming: `method_Scenario_Expectation`
  (`calculate_ZeroVarianceColumns_ReturnsNaNCorrelation`).
- Test packages mirror main 1:1; a test class covers exactly the class it is
  named after. Cross-package flows go in `CorrelationEndToEndTest` at the root.
- Assertion arguments are `(expected, actual)` — expected value first. The one
  sanctioned exception is documented in `FloatMatrixTest`: cross-type
  `assertNotEquals` runs in both directions because JUnit calls `equals` on
  the first argument and each matrix type's `equals` needs exercising.
- Numerical correctness is proven against independent naive implementations
  coded inside the test, on seeded (`new Random(42L)`-style) data — never
  against values produced by the code under test.

## Delivery: Jira, Git, PRs, CI

- **Jira** (project `COR`): use `.claude/tools/jira/jira.sh` — full usage in
  `.claude/skills/jira/SKILL.md`. Every piece of work hangs off an issue;
  epic for the initiative, task per deliverable. Transition to `In Progress`
  when starting, `Done` with a PR/commit reference when finished.
- **GitFlow**: `main` (production) ← `develop` (integration) ← `feature/*`.
  Branch naming: `feature/COR-<n>-eb-<short-description>`. PRs target
  `develop` and are **squash**-merged; only release merges go `develop` →
  `main`, and those use a **true merge commit** (`gh pr merge --merge`),
  never squash — squashing freezes the develop/main merge-base so every
  later release PR re-shows the full develop history. After a release,
  back-merge main into develop and bump the pom to the next `-SNAPSHOT`.
  Full procedure: `.claude/skills/release/SKILL.md`.
- **Commit style**: conventional commits with scope and issue key, e.g.
  `feat(lib): [COR-305]: add float kernels`; body explains the why.
  GPG signing fails under WSL ("Unusable secret key") — use
  `git commit --no-gpg-sign` from WSL and say the commit is unsigned, or
  sign from Windows.
- **GitHub** (`tarvyn-analytics/corrcalc-lib`, private): use the `gh` CLI
  directly — `gh pr create --base develop --title "feat(lib): [COR-n]: ..."`,
  `gh pr checks --watch`, `gh pr merge --squash`, `gh run watch`.
- **CI** (`.github/workflows/`): `validate-on-pull-request.yml` runs
  `./mvnw -Ppublish clean verify` on PRs to develop/main and uploads the
  JaCoCo report; `build-on-push.yml` publishes the jar (with sources and
  javadoc) to GitHub Packages on pushes — develop publishes the SNAPSHOT,
  main strips the suffix, publishes the release and pushes the `vX.Y.Z`
  tag. The same release version
  cannot be published twice, so bump the version on develop before each
  release merge to main. `benchmark.yml` is **manual dispatch only** — a
  sanity check on a hosted runner; its numbers are indicative and never
  published. Official benchmark numbers come exclusively from the reference
  machine (see the hot-loop guide above): the README snapshot table is
  refreshed in perf-relevant PRs, the release table by the release skill.
  PR validation builds the whole reactor, so bench compile breakage
  surfaces there. No DB, no Docker — keep it that way.
