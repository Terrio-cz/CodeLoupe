# Contributing to CodeLoupe

Issues and pull requests are welcome. Keep a change small, tested and documented; a maintainer reviews it before it is
merged. By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). A security problem does not belong
in a public issue: see [SECURITY.md](SECURITY.md).

Before you start on something big, open an issue that describes the problem and what you plan, so that nobody builds
what will not be merged.

## What you need

- JDK 25 (the Gradle wrapper is in the repository; CI uses Temurin 25)
- Node.js 22 or newer (`app/` declares `>=22.12`) for the desktop app and the scripts in `tools/`
- git; ripgrep and network access only for the benchmark script

## Build and test

```bash
./gradlew installDist                  # build/install/codeloupe/bin/codeloupe
./gradlew test --console=plain         # the whole Kotlin suite (about 3 minutes)

cd app
npm ci --ignore-scripts                # install scripts stay off: nothing here needs the Electron binary
npm run lint && npm run typecheck && npm test && npm run build
cd ..

node --test tools/*.test.mjs           # tests of the helper scripts, including the private-names check
node tools/check-wiki-links.mjs        # every link of docs/wiki and the wiki links of the README
```

CI ([ci.yml](.github/workflows/ci.yml)) runs these on Linux, Windows and macOS, and also builds the bundle and the
installers, installs them and runs them. [CodeQL](.github/workflows/codeql.yml) scans every push, and
[licenses.yml](.github/workflows/licenses.yml) fails on a dependency whose licence is not on the allow-list
(`./gradlew checkLicense`, `node tools/npm-licenses.mjs app/package-lock.json`). A pull request needs a green CI.
The Kotlin tests use fixture repositories (`TestRepos`) and need no network.

Two checks surprise newcomers:

- **Private names.** The repository is public, and a test (`tools/private-names.test.mjs`) fails when a tracked text file
  contains a word on a list of private-system names that is kept as hashes. If it fails on your change, remove the word;
  do not add real project names, hosts, paths of your machine or tokens to code, tests or docs.
- **Wiki links.** A link inside `docs/wiki` is a page name (`[text](Page-Name#anchor)`) or a full
  `https://github.com/Terrio-cz/CodeLoupe/blob/main/...` URL of a file that exists; relative paths and `.md` suffixes do
  not work in a wiki. `check-wiki-links.mjs` checks this.

## Where things are

The Kotlin daemon and CLI are in `src/main/kotlin/codeloupe` (one package per concern), the desktop app in `app/`, the
Claude Code plugin in `plugin/`, helper scripts in `tools/`, and the manual in `docs/wiki/`. The
[Development](https://github.com/Terrio-cz/CodeLoupe/wiki/Development) page has the package table, how to measure, and
where each document lives. The wiki is edited as files in `docs/wiki` in a normal change, never in the GitHub wiki editor.

## Running a daemon while you develop

Never point a development build at your own daemon's data or its default port (47391). Use a throwaway home and a port of
your own, and stop it afterwards:

```bash
export CODELOUPE_HOME=/path/to/an/empty/scratch/directory
export CODELOUPE_PORT=47651
build/install/codeloupe/bin/codeloupe start
build/install/codeloupe/bin/codeloupe status
# ... try your change ...
build/install/codeloupe/bin/codeloupe stop
```

The scripts in `tools/` that start a daemon (`profile.mjs`, `benchmark.mjs`, `load-test.mjs`, `rss-mix.mjs`) do this
themselves. Do not paste a real secret into a test, a fixture or an issue; use throwaway values.

## Code

- Kotlin: each production top-level class, interface, object or enum in its own `.kt` file named after it, package equal
  to the folder. Keep classes and functions small and single-purpose, comments only for a non-obvious reason, and match
  the style around you. Tests sit next to the code in `src/test/kotlin`.
- Behaviour that users can see changes the matching page in `docs/wiki` (and the README when it is the landing text) in the
  same pull request. A measured claim in the docs needs the measurement.
- `.editorconfig` holds the whitespace rules; text files are committed with LF line endings (`.gitattributes`).

## Commits and pull requests

- One logical change per pull request, based on `main`, with a short imperative lowercase subject, for example
  `fix outline of an interface with a companion`. No sign-off, trailers or generated-by lines are needed.
- The maintainers track work as cards named `CL-<n>`, and their commit subjects start with the card, for example
  `CL-142 tell the user when ...`. The release notes ([Packaging and releasing](https://github.com/Terrio-cz/CodeLoupe/wiki/Packaging-and-releasing#changelog))
  group commits by that prefix; a commit without it is listed under "Other", which is fine for an outside contribution.
- The [pull request template](.github/pull_request_template.md) asks what and why, how you tested it and what you updated.

## Licence of contributions

CodeLoupe is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE), not an open-source licence. When
you submit a contribution, you agree that it is provided under the same terms as the project, that is PolyForm
Noncommercial 1.0.0, and that you have the right to submit it. There is no contributor licence agreement to sign.
