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
