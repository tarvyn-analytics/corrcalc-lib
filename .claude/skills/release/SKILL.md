---
name: release
description: Cut a corrcalc-lib release — merge develop into main with a true merge commit, publish to GitHub Packages, tag, back-merge, bump the version
---

# Release Skill — corrcalc-lib

GitFlow releases use **true merge commits**, never squash. Squash commits are
for feature → develop only: a squashed release never advances the
develop/main merge-base, so every later release PR would show the entire
develop history again.

## Procedure

1. **Preconditions.** develop is green and its pom version is the
   `X.Y.Z-SNAPSHOT` you intend to release. The release version `X.Y.Z` must
   not already exist in GitHub Packages (it rejects re-publishing).

2. **Release PR.**

   ```bash
   gh pr create --base main --head develop \
     --title "release(lib): [COR-<n>]: release X.Y.Z" \
     --body "Release merge develop -> main. Publishes corrcalc-lib X.Y.Z to GitHub Packages."
   gh pr checks <pr> --watch
   gh pr merge <pr> --merge --admin   # merge commit, NOT --squash
   ```

   `--admin` is needed because the review requirement applies and this is a
   single-maintainer repo (`enforce_admins` is off by design).

3. **CI does the rest** (`build-on-push.yml` on main): strips `-SNAPSHOT`,
   runs the full verify, publishes jar + sources + javadoc to GitHub
   Packages, and pushes the `vX.Y.Z` tag automatically. Watch it:

   ```bash
   gh run list --branch main --workflow build-on-push.yml --limit 1
   gh run watch <run-id> --exit-status
   ```

4. **Back-merge main into develop** so the merge-base advances to the
   release point:

   ```bash
   git checkout develop && git pull --ff-only
   git fetch origin main
   git merge --no-ff -X ours origin/main \
     -m "chore(lib): [COR-<n>]: back-merge main after X.Y.Z release"
   ```

   `-X ours` keeps develop's side on conflicts (the pom version line —
   develop moves past the released version in step 5).

5. **Bump develop** to the next `-SNAPSHOT` (edit the pom `<version>`,
   commit `chore(lib): [COR-<n>]: bump version to X.Y.(Z+1)-SNAPSHOT`).
   Push steps 4+5 together directly to develop — the branch-protection
   bypass allowance covers maintainer pushes.

6. **Benchmark the release** on the reference machine (i7-6820HQ — official
   numbers never come from CI) and record it in the README release table.
   Build from the tag so the measured code is exactly what was published:

   ```bash
   git checkout vX.Y.Z
   ./mvnw --batch-mode -DskipTests clean package
   java -jar corrcalc-lib-bench/target/benchmarks.jar -rf json -rff corrcalc-lib-bench/results/$(date +%F)-X.Y.Z.json
   git checkout develop          # the results JSON is untracked and carries over
   python3 corrcalc-lib-bench/update_benchmark_readme.py corrcalc-lib-bench/results/<that-file>.json README.md \
     --section release --version X.Y.Z --commit "$(git rev-parse --short vX.Y.Z)" \
     --runner "Intel Core i7-6820HQ (4 cores, WSL2)"
   git add corrcalc-lib-bench/results README.md
   git commit -m "docs(bench): [COR-<n>]: record vX.Y.Z benchmark results"
   git push
   ```

   Run it on an otherwise idle machine — close heavy apps first; the full
   suite takes ~10 minutes.

7. **Verify**: the develop push publishes the new SNAPSHOT; check
   `gh api "orgs/tarvyn-analytics/packages/maven/ch.tarvynanalytics.corrcalc.corrcalc-lib-core/versions" --jq '.[].name'`
   lists the release and the new SNAPSHOT, and `git ls-remote --tags origin`
   shows `vX.Y.Z`.

## Why not squash releases

GitHub computes a PR's diff and commit list from the merge-base of the two
branches. A squash merge writes a commit that exists only on main, so the
merge-base stays frozen at the first divergence point forever. A true merge
commit makes main contain develop's actual history, and the back-merge makes
develop contain the release commit — the merge-base then advances to the
release, and the next release PR shows only what's new.
