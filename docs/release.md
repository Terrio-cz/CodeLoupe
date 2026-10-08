# Releasing

A release is a tag. `git tag v1.2.3 && git push origin v1.2.3` runs [release.yml](../.github/workflows/release.yml):

1. **version** – reads the tag (`v<major>.<minor>.<patch>` with an optional `-suffix`, e.g. `v0.0.1-rc1`) and refuses
   anything else.
2. **ci** – the whole of [ci.yml](../.github/workflows/ci.yml) with that version: Kotlin tests, app checks, the bundle,
   the installer and the installer smoke test on every OS. A red job stops the release.
3. **release** – downloads the installers and bundle zips, adds the SBOMs, `SHA256SUMS.txt` and the release notes, and
   creates a **draft** GitHub Release (a prerelease when the version has a suffix). Publishing it is a person's decision.

`workflow_dispatch` (Actions → Release → Run workflow, version e.g. `0.0.1-rc1`) is a dry run: the same files as the
`release-files` artifact, no release.

## What a release holds

| File | Content |
|---|---|
| `CodeLoupe-<v>-win-x64.exe`, `-mac-arm64.dmg`, `-mac-x64.dmg`, `-linux-x64.AppImage`, `-linux-amd64.deb` | Installers with the daemon and its Java runtime (see the README, Installers) |
| `codeloupe-<v>-<os>-<arch>.zip` | The bundle alone: `bin/`, `lib/`, `runtime/` |
| `codeloupe-<v>-daemon-sbom.cdx.json` | CycloneDX SBOM of the daemon and CLI (Gradle runtime classpath) |
| `codeloupe-<v>-app-sbom.cdx.json` | CycloneDX SBOM of the app's npm dependencies that ship (no dev tooling). Electron and Chromium come inside the installer with their own `LICENSES` files |
| `packaging-manifests.zip` | winget, Scoop and Homebrew manifests generated from the files above (see below) |
| `SHA256SUMS.txt` | SHA-256 of every file above |

The release notes list one line per card (`CL-<n>` and its commit subjects since the previous `v*` tag), merges
skipped, commits without a card under *Other*. Card titles are not in them: the workflow has no tracker access.

## One version everywhere

The tag's version goes into Gradle (`-PreleaseVersion`: jar name, `build.properties`, `codeloupe --version`, `/status`,
the bundle name) and into the app (`electron-builder -c.extraMetadata.version`: installer names, Settings → Version,
the sidebar). The installer smoke test of a release checks all of them (`--expect-version`). A development build has
Gradle version `0.1.0` and the app's `package.json` version.

## Package-manager manifests (CL-130)

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
test verifies the installed copy. The manual allow steps for a browser download are in the README, Installers.

## Not yet

Auto-update feed files for electron-updater (CL-107).
