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
- **Windows tests on four runners, Linux on two.** `gradle/test-shards.gradle.kts` runs a share of the classes for
  `-PtestShard=1/4`; a class belongs to a share by its name, and the 37 classes of 10 s and more are placed by their measured
  Windows time, longest first, each to the share with the least so far (`TestShardsTest` fails when one of them no longer exists).
  The first share keeps the name `test (windows-latest)` / `test (ubuntu-latest)`, the others are `test (<os>, shard <n>)`; all of
  them are required checks of the ruleset on main. macOS is not split: macOS runners are scarce (a run that asked for a sixth at
  once waited six minutes for a free one), so one more job there costs more than it gains.
- **The macOS Intel bundle does not compile** (job `dist`). The jars, the launchers and the JDK module list (`bundleModules`,
  jdeps) are the same on every OS; `dist` builds them on Linux in about 85 s and `bundle (macos-15-intel)` lays out its bundle from
  them (`-PprebuiltInstall`), linking only the runtime. The Intel runner took 160 to 460 s for `./gradlew bundle` before (Kotlin
  compile up to 250 s, jdeps over the Kotlin compiler up to 90 s); the job now takes 242 to 329 s instead of 360 to 825 s. The
  steps are shared with the other `bundle` legs through a YAML anchor; the other three still compile themselves, because waiting
  for `dist` would cost them more than the compile.
- **A hung test fails by name.** `junit.jupiter.execution.timeout.default` is 5 minutes (the longest test takes 1.5), so a hang
  like the one of run 38181094368 fails that test instead of running the job into its 20 minute limit with no word.
- **Forks per runner.** Two forks on a hosted Linux or Windows runner (4 vCPU) and three on a macOS runner (3 cores). Four
  forks on the four cores were tried and removed: Windows 599 s against 660 s with two, Linux 393 s against 330 to 400 s, the
  summed class time grew from 900 to 1566 s, and `IngestScaleTest`, `SessionStartDaemonTest` and `HooksDaemonTest` failed on
  three of the six runs. Three forks on macOS took 310 to 400 s against 600 to 690 s with two.
- **Gradle gets 2 GB** (`GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx2g` for the whole workflow) instead of the 1 GB of
  `gradle.properties`; the Kotlin compiler daemon inherits it.
- **Flaky tests found on the way** (all test code): `PostEditSampleTest` read a source saved as Latin-1
  (`BudgetsConfig.kt`); the hook script tests wrote to a script that had already exited (`Process.feed`); the session start
  test counted the `was:` line as a caller line; `IngestScaleTest` judged one sample of a 200 ms read (now the fastest of three).
  Second round (2026-10-14): the child JVMs of `ProcessStopperTest` were taken as settled after half a second without CPU, which a
  JVM waiting for the disk on a loaded runner also is (now they print `ready` from their main method first, both ways it failed);
  `ParseWorkerTest` counted the parse workers of every JVM on the machine, also the other fork's; `JobsTest` gave a surviving job
  2.5 s to outlive the start of the next daemon (now 10 s) and read a killed job as alive before the OS had reaped it.
- **Unknown means everything.** A new branch, a tag, a force push that dropped the base, a scheduled run, or a failure of the
  `changes` job itself runs every step: the answer is skipped only when it is known to be `false`.

A documentation-only push (branch CL-195, CI run 38071077346 and CodeQL run 38071077352): CI 30 s, CodeQL 10 s, both green.
Every required job reported with its steps skipped, 7 to 14 s each; `update test`, `clipboard`, `libsecret`, `manifests` and
`installer smoke` were skipped whole. Against 14 minutes for the same push before.

### Trial runs of the final setup (branch CL-195, 2026-10-11)

"Kotlin only" means the update test was skipped as a whole job; seconds are the job time.

| Run | Push | Update test | Wall | test windows 1 / 2 | test linux | test macOS | bundle Intel | Result |
|---|---|---|---|---|---|---|---|---|
| 38158922783 | Kotlin and scripts | skipped | 615 s | 484 / 461 | 450 | 599 (2 forks) | 474 | green |
| 38175517043 | workflow comment | 637 s | 802 s | 510 / 454 | 313 | 635 (2 forks) | 677 | green |
| 38176508764 | Kotlin only | skipped | 674 s | 516 / 414 | 419 | 656 (2 forks) | 504 | green |
| 38177390315 | workflow, macOS forks | 616 s | 644 s | 512 / 503 | 439 | 403 | 546 | green |
| 38178234939 | Kotlin only | skipped | 668 s | 480 / 350 | 394 | 394 | 425 | green |
| 38179297126 | merge of main, docs | 733 s | 746 s | 491 / 313 | 463 | 482 | 462 | red: update test (Linux) read the AppImage while it was swapped |
| 38180195012 | update test fix | 634 s | 654 s | 458 / 490 | 417 | 416 | 563 | red: `OverlayTest` read the sync counter too early |
| 38181094368 | Kotlin only | skipped | 507 s | 493 / 479 | 321 | 421 | 356 | green |
| 38181094368, 2nd attempt | the same | skipped | - | hung / 478 | 380 | 353 | 528 | red: `ProcessStopperTest` (busy by CPU on a loaded runner); Windows share 1 hung until the 20 minute limit |

Five green runs in a row (rows 1 to 5; rows 3 to 5 are the three of the criterion), then two red ones for a flaky test each (fixed, rows 6 and 7), a green one and a red
one. The two flaky tests found last are not fixed: `ProcessStopperTest` fails when the daemon used CPU in the last 400 ms,
which a loaded runner does (it failed on main before this work, run 38142734610), and one Windows share hung once with no
output, so the test that hung is unknown. Medians of the three consecutive green runs: the Windows `test` job 512 s
(before, with two forks in one job: 708 s median of five main runs, 766 s in the run that set the target), the second share
414 s, the Linux job 419 s, the whole run 668 s. Median of the six green runs 656 s, of the Kotlin-only ones (615, 674,
668, 507) 641 s; before: 752 s median of the four green main runs of 2026-10-11, 12.5 minutes, 14 minutes over the 23
runs of 2026-10-09 and 10. The Intel runner started 164 s after the push in the run of row 5; the run of row 8 had no wait and took 8.5 minutes.

### Second round (branch CL-195, 2026-10-14)

Main before it: 588 to 926 s, median 810 s over twelve finished runs of 2026-10-13 and 14 (one more took 1618 s); the long poles were `bundle (macos-15-intel)` (360 to
825 s, the Intel runner is fast or up to three times slower from run to run), `update test (windows-latest)` (657 to 729 s) and the
first Windows share (544 to 615 s). Changes: the `dist` job for the Intel bundle, Linux in two shares, Windows in three and then four,
the flaky tests above.

| Run | Push | Wall | Longest job | test windows | test linux | test macOS | bundle Intel (wait) | update test windows | Result |
|---|---|---|---|---|---|---|---|---|---|
| 38547672973 | Kotlin, 3 Windows shares | 770 s | test linux 573 s | 517 / 439 / 556 | 573 | 304 | 274 (260 s queued) | skipped | green |
| 38549341524 | workflow, Linux in two | 709 s | update test 698 s | 618 / 409 / 406 | 312 / - | 477 | 329 (175 s) | 698 s | green |
| 38551078789 | workflow, 4 Windows shares | 1054 s | test macOS 652 s | 366 / 371 / 393 / - | 306 / 351 | 652 | 242 (527 s) | 709 s | green |

The jobs themselves now fit in 10 minutes except the Windows update test; the wall time does not, because of waiting for runners. A
push runs about 25 jobs, 8 of them on macOS, and the account runs 20 jobs at once, 5 on macOS, shared with every branch the agent
windows push at the same time: in the third run the macOS test started 402 s after the push and the Intel bundle 527 s. That is the
reason the criterion of 10 minutes is not met steadily, not the work in the jobs.

The Windows update test stays at about 700 s (476 s for two Gradle builds and three NSIS installers, 180 s for the test); it runs
only when the app, the build or the workflows changed. Building the installers uncompressed was tried (`-c.compression=store`): the
NSIS step still took 74 s against 74 s, it is bound by the disk, not by LZMA, so it was dropped. Ideas not taken: building the three
installers in parallel (they share `app/stage` and `app/dist`), `-Xbackend-threads=0` for the Kotlin compiler, and excluding the
workspace from Windows Defender (a change of the runner's security settings).
