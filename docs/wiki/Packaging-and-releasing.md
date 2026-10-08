How CodeLoupe is built into something a user can install, and how a release is made. The user-side view is in [Installers
and updates](Installers-and-updates).

## Bundle

```bash
./gradlew bundle     # build/distributions/codeloupe-<version>-<os>-<arch>.zip
```

The zip holds `bin/` (launchers), `lib/` (jars) and `runtime/`, a jlink runtime with only the modules the jars use
(found by `jdeps`) plus the ones needed at run time, minus `jdk.compiler` (the parser never runs javac) and without the
files jlink leaves that nothing uses: the class-data archive for heaps over 32 GB (`classes_nocoops.jsa`) and `jvm.lib`
(`java.desktop` has to stay: without it the parser worker returns no facts, which `tools/bundle-smoke.mjs` would show). The launchers prefer `runtime/` to any JDK on the machine, so the
bundle runs without Java. jlink output runs only on the OS it was built on, so CI builds one bundle per OS
(`bundle` job in [ci.yml](https://github.com/Terrio-cz/CodeLoupe/blob/main/.github/workflows/ci.yml)); the Electron installer takes the same directory (see [Installers](#installers)).
`node tools/bundle-smoke.mjs <bundle dir>` runs a query on a PATH without any Java and prints the sizes and the
daemon's RSS; CI runs it on every push and keeps the numbers as `bundle-report-<os>` artifacts.

Measured in CI on 2026-10-08 (Temurin 25.0.4, tiny repository, daemon idle after its first index build):

| OS | Zip | Unpacked (runtime) | Daemon RSS | First / warm query |
|---|---|---|---|---|
| Linux x64 | 139.8 MB | 195 MB (105 MB) | 109 MB | 3.6 s / 0.25 s |
| Windows x64 | 135.3 MB | 182 MB (92 MB) | 104 MB | 9.1 s / 0.34 s |
| macOS arm64 | 134.4 MB | 185 MB (95 MB) | 94 MB | 2.3 s / 0.16 s |

The first query includes starting the daemon and creating the class-data archive.

## Installers

Per OS, one download that needs no Java: the desktop app with the bundle above inside (`resources/codeloupe`) and the
Claude Code plugin (`resources/claude-plugin`). `./gradlew bundle`, then in `app/`: `npm ci && npm run dist`
(electron-builder, config in [app/electron-builder.yml](https://github.com/Terrio-cz/CodeLoupe/blob/main/app/electron-builder.yml)). The CPU is the one the build runs
on, because the runtime is. CI builds them in the `bundle` job and keeps them for 7 days as `installer-<os>` artifacts.

The `installer-smoke` CI job installs each installer on its OS, starts the app, waits for the daemon the app starts
from the bundled runtime, runs `find` through the CLI and through the MCP endpoint on a PATH without Java, takes a
screenshot of the app window (artifact `smoke-<os>`) and uninstalls (`node tools/installer-smoke.mjs <installer>`;
it uses its own home, port and app data, so it is safe on a developer machine; screenshots only when `CI` is set).
CI cost and runners: [docs/ci.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/ci.md). Why the installers are unsigned, what signing would cost and what changes if that is decided otherwise: [docs/code-signing.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/code-signing.md).

## Releasing

A release is a tag. `git tag v1.2.3 && git push origin v1.2.3` runs [release.yml](https://github.com/Terrio-cz/CodeLoupe/blob/main/.github/workflows/release.yml):

1. **version** – reads the tag (`v<major>.<minor>.<patch>` with an optional `-suffix`, e.g. `v0.0.1-rc1`) and refuses
   anything else.
2. **ci** – the whole of [ci.yml](https://github.com/Terrio-cz/CodeLoupe/blob/main/.github/workflows/ci.yml) with that version: Kotlin tests, app checks, the bundle,
   the installer and the installer smoke test on every OS. A red job stops the release.
3. **release** – downloads the installers and bundle zips, adds the SBOMs, `SHA256SUMS.txt` and the release notes, and
   creates a **draft** GitHub Release (a prerelease when the version has a suffix). Publishing it is a person's decision.

`workflow_dispatch` (Actions → Release → Run workflow, version e.g. `0.0.1-rc1`) is a dry run: the same files as the
`release-files` artifact, no release.

### What a release holds

| File | Content |
|---|---|
| `CodeLoupe-<v>-win-x64.exe`, `-mac-arm64.dmg`, `-mac-x64.dmg`, `-linux-x86_64.AppImage`, `-linux-amd64.deb` | Installers with the daemon and its Java runtime (see [Installers and updates](Installers-and-updates)) |
| `codeloupe-<v>-<os>-<arch>.zip` | The bundle alone: `bin/`, `lib/`, `runtime/` |
| `codeloupe-<v>-daemon-sbom.cdx.json` | CycloneDX SBOM of the daemon and CLI (Gradle runtime classpath) |
| `codeloupe-<v>-app-sbom.cdx.json` | CycloneDX SBOM of the app's npm dependencies that ship (no dev tooling). Electron and Chromium come inside the installer with their own `LICENSES` files |
| `latest.yml`, `latest-linux.yml`, `CodeLoupe-<v>-win-x64.exe.blockmap` | The update feed of electron-updater: SHA-512 and size of the Windows installer and the AppImage; the blockmaps let a download fetch only what changed. The release job refuses a feed that does not match the installers beside it (`tools/feed-check.mjs`). There is no `latest-mac.yml`: macOS only notifies |
| `packaging-manifests.zip` | winget, Scoop and Homebrew manifests generated from the files above (see below) |
| `SHA256SUMS.txt` | SHA-256 of every file above |

The release notes list one line per card (`CL-<n>` and its commit subjects since the previous `v*` tag), merges
skipped, commits without a card under *Other*. Card titles are not in them: the workflow has no tracker access.

### One version everywhere

The tag's version goes into Gradle (`-PreleaseVersion`: jar name, `build.properties`, `codeloupe --version`, `/status`,
the bundle name) and into the app (`electron-builder -c.extraMetadata.version`: installer names, Settings → Version,
the sidebar). The installer smoke test of a release checks all of them (`--expect-version`). A development build has
Gradle version `0.1.0` and the app's `package.json` version.

### Package-manager manifests

The release job runs `tools/packaging-manifests.mjs` on the release files and attaches `packaging-manifests.zip`:

| Path in the zip | Goes to |
|---|---|
| `winget/manifests/t/Terrio/CodeLoupe/<v>/` (three YAML files, `Terrio.CodeLoupe`) | a pull request to [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) (`winget validate --manifest <dir>` checks them first) |
| `scoop/codeloupe.json` | a Scoop bucket (the manifest has `checkver` and `autoupdate`, so a bucket follows new releases by itself) |
| `homebrew/Casks/codeloupe.rb` | a Homebrew tap (`brew style --cask` checks it; CI does) |

The URLs point at the release's assets, hashes come from `SHA256SUMS.txt` (an installer present on disk must match
it), so the draft release must be published before a manifest is submitted. Nothing is submitted by the pipeline: that is
an outward-facing step the owner takes. Names and licence (`PolyForm-Noncommercial-1.0.0`) should be checked against
winget-pkgs' policy before the first submission. The Scoop manifest unpacks the NSIS installer as an archive instead of
running it, so a Scoop install is portable and Scoop alone updates it.

macOS builds are signed ad hoc by the `afterPack` hook `app/scripts/ad-hoc-sign.mjs`; the `bundle` job checks
`codesign -dv` (`Signature=adhoc`) and `codesign --verify --deep --strict` on both macOS runners, and the installer smoke
test verifies the installed copy. The manual allow steps for a browser download are in [Installers and updates](Installers-and-updates#installing-without-a-warning).

### The update feed

The app finds the newest release itself: it reads `github.com/Terrio-cz/CodeLoupe/releases.atom` (published releases only, a
draft is invisible) and, for a newer tag, the files of that release (`releases/download/<tag>/latest.yml`, then the
installer). electron-builder writes the feed files next to the installers (`publish` in `app/electron-builder.yml`, with
`--publish never` nothing is uploaded) and the `bundle` job keeps them with the installers.

- **Name release candidates `rc.N`** (`v0.0.1-rc.1`, `rc.2`, `rc.10`): the number after the dot is compared as a number, so
  `rc.10` follows `rc.2`; `rc10` and `rc2` would be compared as text. A final release follows the last rc.
- **Publishing the draft is what makes an update visible**; until then no installed app sees the release.
- A released feed is trusted by every installed app: do not edit `latest.yml` or the installers in a published release (the
  checks of `tools/feed-check.mjs` run in the release job before the draft is created).

#### Testing an update locally

```
node tools/update-test.mjs --old CodeLoupe-0.9.0-rc.1-win-x64.exe --new CodeLoupe-0.9.0-rc.2-win-x64.exe [--bad CodeLoupe-0.9.0-rc.3-win-x64.exe]
```

Build the installers as the `update-test` job in [ci.yml](https://github.com/Terrio-cz/CodeLoupe/blob/main/.github/workflows/ci.yml) does: `./gradlew bundle
-PreleaseVersion=<v>` and `npm run dist -- -c.extraMetadata.version=<v>` once per version, and for `--bad` the stage of the
second with a jar that cannot start (`node tools/break-bundle.mjs app/stage/codeloupe 0.9.0-rc.3`, then `electron-builder`).
The test serves `latest.yml` and the installer from a throwaway server on 127.0.0.1 (`--port`, default 47550, and the next
port for the daemon) and starts the app with `CODELOUPE_UPDATE_FEED` pointing at it. The variable accepts a loopback address
only, so it cannot make the updater talk to another host; it is not a way to update from somewhere else. The test installs N,
stores a settings file, a secret and an indexed repository, and checks that

- a tampered installer (one byte changed, the feed's SHA-512 unchanged) is refused and nothing is installed;
- with automatic updates off the server receives no request;
- N downloads, verifies and installs N+1, having kept its daemon bundle; the old daemon was stopped (Windows) and the new app
  runs the daemon of the new bundle; settings and the secret are as they were; a repository whose index has an older format
  is rebuilt; every request carries only the bare User-Agent, no cookie and no identifier;
- with `--bad`, an update to a build whose daemon cannot start ends with the previous daemon running from the kept bundle and
  the rollback recorded.

The installer does not start the new app itself in the test (the installer would start it with the user's own environment,
not the test's), so that step, the `--force-run` relaunch of electron-updater, is the one part only a real update exercises.
CI runs the test on Linux (AppImage) and Windows (`update-test` job); macOS has no self-update to test.
