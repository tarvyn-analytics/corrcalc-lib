---
name: release
description: Cut a corrcalc-lib release — merge develop into main with a true merge commit, publish GPG-signed artifacts to Maven Central, tag, back-merge, bump the version
---

# Release Skill — corrcalc-lib

GitFlow releases use **true merge commits**, never squash. Squash commits are
for feature → develop only: a squashed release never advances the
develop/main merge-base, so every later release PR would show the entire
develop history again.

Releases publish `io.github.tarvyn-analytics.corrcalc:corrcalc-lib-core` (and
its parent pom) to **Maven Central** via the Sonatype Central Portal. A version
published to Central is **permanent** — it can never be re-published, replaced,
or deleted. Get develop right before merging.

## Procedure

1. **Preconditions.** develop is green and its pom version is the
   `X.Y.Z-SNAPSHOT` you intend to release. `X.Y.Z` must not already exist on
   Central (it rejects re-publishing) — check
   `https://repo1.maven.org/maven2/io/github/tarvyn-analytics/corrcalc/corrcalc-lib-core/`.

2. **Release PR.**

   ```bash
   gh pr create --base main --head develop \
     --title "release(lib): [COR-<n>]: release X.Y.Z" \
     --body "Release merge develop -> main. Publishes corrcalc-lib X.Y.Z to Maven Central."
   gh pr checks <pr> --watch
   gh pr merge <pr> --merge --admin   # merge commit, NOT --squash
   ```

   `--admin` is needed because the review requirement applies and this is a
   single-maintainer repo (`enforce_admins` is off by design).

3. **CI does the rest** (`build-on-push.yml` on main): strips `-SNAPSHOT`,
   imports the GPG key, then `-Ppublish,release -pl corrcalc-lib-core -am
   clean deploy` builds and signs the parent pom + core jar + sources +
   javadoc and uploads them to the Central Portal
   (`central-publishing-maven-plugin`, `autoPublish=true`,
   `waitUntil=published`), then pushes the `vX.Y.Z` tag. The `-pl … -am`
   module selection is what keeps the never-published bench module out of the
   deployment bundle (`maven.deploy.skip` would NOT — Central bundles ignore
   it). Watch it:

   ```bash
   gh run list --branch main --workflow build-on-push.yml --limit 1
   gh run watch <run-id> --exit-status
   ```

   **If the run shows "cancelled" at the deploy step:** `waitUntil=published`
   can outlast the 20-minute job timeout while Central keeps processing
   server-side — with `autoPublish=true` the release usually still goes out.
   Do **not** re-run the workflow (a second upload of the same version is
   rejected). Instead poll the deployment status (the deployment id is in the
   plugin's CI log output; credentials live in
   `~/.config/corrcalc-graphs/secrets.env`):

   ```bash
   TOKEN=$(printf '%s:%s' "$CENTRAL_TOKEN_USERNAME" "$CENTRAL_TOKEN_PASSWORD" | base64 -w0)
   curl -s -X POST -H "Authorization: Bearer $TOKEN" \
     "https://central.sonatype.com/api/v1/publisher/status?id=<deployment-id>"
   ```

   Once it reports `PUBLISHED`, create the tag the cancelled run skipped:

   ```bash
   gh api "repos/tarvyn-analytics/corrcalc-lib/git/refs" \
     -f ref="refs/tags/vX.Y.Z" -f sha="$(git rev-parse origin/main)"
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
   bypass allowance covers maintainer pushes. Note develop publishes nothing
   anywhere (Central takes release versions only) — it is the quality gate.

6. **Benchmark the release** on the reference machine (i7-6820HQ — official
   numbers never come from CI) and record it in the README release table.
   Build from the tag so the measured code is exactly what was published:

   ```bash
   git checkout vX.Y.Z
   ./mvnw --batch-mode -DskipTests clean package
   # run on the Windows host via interop — never inside the WSL VM (half the cores)
   cmd.exe /c "java -jar corrcalc-lib-bench\target\benchmarks.jar -p profile=STANDARD,HIGH_PERFORMANCE,VECTORIZED -prof gc -rf json -rff corrcalc-lib-bench\results\$(date +%F)-X.Y.Z.json"
   git checkout develop          # the results JSON is untracked and carries over
   python3 corrcalc-lib-bench/update_benchmark_readme.py corrcalc-lib-bench/results/<that-file>.json README.md \
     --section release --version X.Y.Z --commit "$(git rev-parse --short vX.Y.Z)" \
     --runner "Intel Core i7-6820HQ (8 threads, Windows host)"
   git add corrcalc-lib-bench/results README.md
   git commit -m "docs(bench): [COR-<n>]: record vX.Y.Z benchmark results"
   git push
   ```

   Run it on an otherwise idle machine — close heavy apps first; the full
   suite takes ~10 minutes.

7. **Verify**: `git ls-remote --tags origin` shows `vX.Y.Z`, and the artifact
   is live at
   `https://central.sonatype.com/artifact/io.github.tarvyn-analytics.corrcalc/corrcalc-lib-core/X.Y.Z`.
   The `repo1.maven.org` CDN can lag the PUBLISHED state by some minutes —
   don't panic on a 404 right after publishing.

## Dry-running the bundle without publishing

To validate a release bundle without burning the version, deploy locally with
auto-publish off (needs the Central token + GPG key from
`~/.config/corrcalc-graphs/secrets.env` wired into `~/.m2/settings.xml` the
way CI does):

```bash
./mvnw --batch-mode -Ppublish,release -pl corrcalc-lib-core -am \
  -Dcentral.autoPublish=false -Dcentral.waitUntil=validated clean deploy
```

Central validates the staged deployment; review it in the Portal UI, then
**drop** it (`DELETE https://central.sonatype.com/api/v1/publisher/deployment/<id>`
with the same Bearer token) so nothing is published.

## Why not squash releases

GitHub computes a PR's diff and commit list from the merge-base of the two
branches. A squash merge writes a commit that exists only on main, so the
merge-base stays frozen at the first divergence point forever. A true merge
commit makes main contain develop's actual history, and the back-merge makes
develop contain the release commit — the merge-base then advances to the
release, and the next release PR shows only what's new.
