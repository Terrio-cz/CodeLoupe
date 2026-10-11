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
- **The update test runs only when something it exercises changed.** It installs real installers and follows the update
  from one build to the next, so it answers to `app/` (the updater, the installer configuration), the build (`build.gradle.kts`,
  `gradle/bundle.gradle.kts`, the workflows) and the scripts of the test itself (`tools/update-test.mjs`, `feed-check`,
  `break-bundle`, `no-java-env`, `daemon-token`). A push that besides documentation only changes Kotlin (`src/`), `app/test/`,
  `plugin/`, `gradle/test-*.gradle.kts` or another script in `tools/` skips it. It is not a required check, so the whole job is
  skipped. What Kotlin can still break in an installed build (the daemon does not start) is caught by `bundle` and
  `installer smoke`, which start the daemon of every bundle and installer; the update test builds both of its versions from
  the same commit, so it does not compare two Kotlin versions.
- **Windows tests on two runners.** `gradle/test-shards.gradle.kts` runs a share of the classes for `-PtestShard=1/2`; a class
  belongs to a share by its name (the 15 slowest are placed by hand, alternately, and `TestShardsTest` fails when one of them
  no longer exists). The first share keeps the name `test (windows-latest)` that the ruleset requires, the second is
  `test (windows-latest, shard 2)`; **add the second to the required checks**, because a red second share fails the run but not
  the required check. macOS is not split: macOS runners are scarce (a run that asked for a sixth at once waited six minutes
  for a free one), so one more job there costs more than it gains.
- **Forks per runner.** Two forks on a hosted Linux or Windows runner (4 vCPU) and three on a macOS runner (3 cores). Four
  forks on the four cores were tried and removed: Windows 599 s against 660 s with two, Linux 393 s against 330 to 400 s, the
  summed class time grew from 900 to 1566 s, and `IngestScaleTest`, `SessionStartDaemonTest` and `HooksDaemonTest` failed on
  three of the six runs. Three forks on macOS took 310 to 400 s against 600 to 690 s with two.
- **Gradle gets 2 GB** (`GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx2g` for the whole workflow) instead of the 1 GB of
  `gradle.properties`; the Kotlin compiler daemon inherits it.
- **Flaky tests found on the way** (all test code): `PostEditSampleTest` read a source saved as Latin-1
  (`BudgetsConfig.kt`); the hook script tests wrote to a script that had already exited (`Process.feed`); the session start
  test counted the `was:` line as a caller line; `IngestScaleTest` judged one sample of a 200 ms read (now the fastest of three).
- **Unknown means everything.** A new branch, a tag, a force push that dropped the base, a scheduled run, or a failure of the
  `changes` job itself runs every step: the answer is skipped only when it is known to be `false`.

A documentation-only push (branch CL-195, CI run 38071077346 and CodeQL run 38071077352): CI 30 s, CodeQL 10 s, both green.
Every required job reported with its steps skipped, 7 to 14 s each; `update test`, `clipboard`, `libsecret`, `manifests` and
`installer smoke` were skipped whole. Against 14 minutes for the same push before.

### Trial runs of the final setup (branch CL-195, 2026-10-11)

All green. "Kotlin only" means the update test was skipped as a whole job; seconds are the job time.

| Run | Push | Update test | Wall | test windows 1 / 2 | test linux | test macOS | bundle Intel |
|---|---|---|---|---|---|---|---|
| 38158922783 | Kotlin and scripts | skipped | 615 s | 484 / 461 | 450 | 599 (2 forks) | 474 |
| 38175517043 | workflow comment | 637 s | 802 s | 510 / 454 | 313 | 635 (2 forks) | 677 |
| 38176508764 | Kotlin only | skipped | 674 s | 516 / 414 | 419 | 656 (2 forks) | 504 |
| 38177390315 | workflow, macOS forks | 616 s | 644 s | 512 / 503 | 439 | 403 | 546 |
| 38178234939 | Kotlin only | skipped | 668 s | 480 / 350 | 394 | 394 | 425 |

Five green runs in a row; the last three are the "three in a row" of the criterion. Medians of those three: the Windows
`test` job 512 s (before, with two forks in one job: 708 s median of five main runs, 766 s in the run that set the target),
the second Windows share 414 s, the Linux job 419 s, the whole run 668 s (before: 752 s median of the four green main runs of 2026-10-11, 12.5
minutes, and 14 minutes over the 23 runs of 2026-10-09 and 10). In the last run the Intel runner started 164 s after the
push; without that wait the run took about 8.5 minutes.

What is left on the critical path is not test time but the macOS Intel runner: `compileKotlin` takes 137 to 190 s there
against about 50 s on the other systems, then the bundle, the installer and the installer smoke test follow in series (425 to
677 s plus about 70 s), and a macOS job can wait minutes for a free runner. A run on main is therefore 9 to 11 minutes, not
a steady 10. Ideas not taken: building the jar once and sharing it with the four `bundle` jobs (a larger change of
`gradle/bundle.gradle.kts`), `-Xbackend-threads=0` for the Kotlin compiler, and excluding the workspace from Windows
Defender (a change of the runner's security settings).
