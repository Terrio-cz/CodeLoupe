# CI cost and runners

Workflows: [ci.yml](../.github/workflows/ci.yml) (Kotlin tests, app checks, bundle + installer per OS, installer smoke
tests), [codeql.yml](../.github/workflows/codeql.yml), [licenses.yml](../.github/workflows/licenses.yml). Decisions of
CL-110, measured 2026-10-08.

## Decision: nothing to ration while the repository is public

The repository is public. GitHub-hosted standard runners are free and unlimited there, macOS and Windows included, so
the full matrix runs on every push and no job is held back for cost. What would change if it became private is below.

## What one push costs

One CI run (all 14 jobs, run 37780010337, after the cache hits of Gradle and npm):

| OS | Jobs (min) | Runner minutes | Billed if private |
|---|---|---|---|
| Linux | test 2.0, app 0.4, bundle + installers 5.6, smoke 2.1 | 10.0 | 10.0 |
| Windows | test 4.1, app 1.0, bundle + installer 4.6, smoke 1.1 | 10.8 | 21.5 (×2) |
| macOS arm64 | test 2.2, app 0.5, bundle + installer 4.1, smoke 1.0 | 7.7 | 77.2 (×10) |
| macOS x64 | bundle + installer 7.8, smoke 1.1 | 8.9 | 88.5 (×10) |
| **Total** | | **37.4** | **≈ 197** |

On 2026-10-08, 42 CI runs started between 08:14 and 12:34 (many windows pushing branches; superseded runs of a branch are
cancelled by `concurrency`). At about 150 runs a month a private repository would use about 30 000 billed minutes against
2 000 (Free) or 3 000 (Team) included, so the macOS jobs alone would exhaust the quota in about ten pushes.

## Caching

- npm (`setup-node`) and Gradle (`setup-gradle`) caches are on in every job that needs them.
- A cache of the electron-builder downloads (Electron, NSIS, fpm, AppImage tools; 157–202 MB per OS) was tried: the
  installer build did not get faster beyond noise (Windows 74–96 s → 86 s, Linux 212 → 165 s, macOS arm64 68 → 71 s,
  macOS x64 92 → 132 s), because compression and packaging dominate, not the downloads. It was removed; each entry would
  have taken a share of the 10 GB cache budget that the Gradle caches need.

## Self-hosted runner: no

The Terrio VPS runner is not shared with this repository. In a public repository a pull request from a fork runs its
workflow code on the runner, and the VPS also holds Terrio's own secrets and Docker. Hosted Linux minutes are free here
and macOS and Windows need hosted runners anyway. Revisit when the repository goes private or Linux queue time hurts.

## If the repository becomes private

In this order, until a push costs about 10 billed minutes:

1. Run the macOS and Windows legs of `bundle` and `installer-smoke` only on `main`, release tags and a nightly schedule
   (a matrix `include` keyed on `github.event_name` and `github.ref`); keep Linux on every push.
2. Keep `concurrency` with `cancel-in-progress` (already on) and skip `app` and `installer-smoke` for pushes that only
   touch Kotlin or docs (a `changes` job on `git diff --name-only`).
3. Re-measure with this table and check Settings → Billing → Actions after two weeks.

## Wall time of a push to main (CL-195)

Measured over the 23 successful CI runs of 2026-10-09 and 10: 12 to 19 minutes, median 14. The run takes as long as its
slowest job. On run 38054115886 that was `test (windows-latest)` at 14.5 minutes (868 s of `./gradlew test`; Linux 425 s),
then `update test (windows-latest)` at 11 minutes; the bundles take 5 to 7.5 minutes and the installer smoke tests one.

- **Parallel test forks.** `tasks.test` sets `maxParallelForks` to half the CPUs, at least 1 and at most 2, so the 216 test
  classes no longer run one after another in one JVM. `-PtestForks=N` overrides it. Capped at 2 so that a developer
  machine with many agent windows is not flooded.
- **Documentation-only pushes run almost nothing.** The `changes` job (`tools/changed-paths.mjs`) compares the push with its
  base. When every changed file is under `docs/`, markdown, the licence or an issue template, the steps of `test`, `app` and
  `bundle` and of both CodeQL analyses are skipped, and `manifests`, `libsecret`, `clipboard`, `installer smoke` and
  `update test` do not run. The required checks still report: the ruleset on main requires `tools`, `test` on all three
  systems, `app (ubuntu)`, `bundle (ubuntu)` and both CodeQL analyses, and a workflow filtered out by `paths-ignore`, or a
  matrix job skipped by a job-level `if`, never reports them (a skipped matrix job is reported under its unexpanded
  name), so those jobs start and skip their steps, which takes a runner start (about half a minute) instead of the run.
  `tools` always runs; it checks the wiki links and the workflows. Tests, the build, the workflows and the plugin count as
  code.
- **The update test also skips tests and the plugin.** A push that only changes `src/test/`, `app/test/` or `plugin/` (besides
  documentation) cannot change what an update does. It is not a required check, so the whole job is skipped.
- **Unknown means everything.** A new branch, a tag, a force push that dropped the base, a scheduled run, or a failure of the
  `changes` job itself runs every step: the answer is skipped only when it is known to be `false`.

Trial run 1 (branch CL-195, run 38070132858; a new branch, so every step ran): 12 minutes, all green.

| Job | Before (run 38054115886) | Now |
|---|---|---|
| test (windows-latest) | 868 s | 465 s |
| test (ubuntu-latest) | 425 s | 340 s |
| test (macos-latest) | 445 s | 479 s |
| update test (windows-latest) | 655 s | 634 s |
| bundle (macos-15-intel) | 437 s | 650 s |

The Windows test is 46 % shorter. The run is now bounded by `bundle (macos-15-intel)` and the installer smoke test after it
(650 s + 84 s), not by the tests; the Intel runner varies by minutes between runs. Further gains would come from the macOS
Intel bundle and from the update test, which is unchanged for a push that touches code.
