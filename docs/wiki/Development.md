Building, testing and measuring CodeLoupe, and where the code and the documents live. The code is Kotlin on the JVM
(the daemon and the CLI, `src/`) and an Electron/React desktop app (`app/`).

## Build and test

```bash
./gradlew installDist     # build/install/codeloupe/bin/codeloupe
./gradlew test            # the whole Kotlin suite (about 3 minutes)
cd app && npm ci --ignore-scripts && npm run lint && npm run typecheck && npm test && npm run build
node --test tools/*.test.mjs   # the Node tests of the helper scripts
node tools/check-wiki-links.mjs   # every link of the wiki and of the README
```

Always run a development daemon with a throwaway `CODELOUPE_HOME` and a port of its own (`CODELOUPE_PORT`), so it never
touches the daemon you use; the scripts below do that themselves. CI ([ci.yml](https://github.com/Terrio-cz/CodeLoupe/blob/main/.github/workflows/ci.yml)) runs the same checks on three
operating systems; see [Packaging and releasing](Packaging-and-releasing).

## Measuring

`node tools/profile.mjs --cli build/install/codeloupe/bin/codeloupe --home <tmp> --root <repo> --worktree <worktree>`
profiles a warm query, the first query in a worktree and (with `--clone`) an overlay refresh: client latency split
by the daemon's own timings (`/status` `timings`, `gitSpawns`) into git, worktree walk, SQL, the rest of the tool and HTTP.
`--only edit` runs just the refresh rows, after `--warmup` (2) cycles that are not counted.

`node tools/benchmark.mjs --work <scratch dir>` reproduces [docs/benchmarks.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/benchmarks.md) after `./gradlew installDist`
(needs git, ripgrep and network; about 25 minutes). It clones the public repositories at pinned commits into the scratch
directory, installs GitNexus there from npm (`--no-gitnexus` skips it, `--rg <binary>` points at a ripgrep that is not on
`PATH`), starts its own daemon on port 47651 (`--port`) with a throwaway home, never touches the daemon on the default
port, and writes `docs/benchmarks.md`, `.json` and the branded charts (`docs/benchmarks*.svg`, light and dark, drawn by `tools/benchmarkCharts.mjs`); `--report-only docs/benchmarks.json` rewrites the markdown and
the chart from a saved run.

`node tools/load-test.mjs --install build/install/codeloupe --source <repo>` clones the repository into a scratch directory, adds
eight worktrees and runs ten client loops against a throwaway daemon; midway it commits a change to the default branch (a base
sync) and edits files in four worktrees. It prints p95 latency before and during the sync, busy and failed calls, and RSS (steady,
peak, series) against the budgets of `docs/plan.md` § 2. `node tools/rss-mix.mjs --install … --source <repo>` runs 50 mixed
queries (`changes bodies`, `calls … callees depth 3`, `usages` included) and reports the resident memory with the JVM's own
accounting (`--jvm-opts` to try flags, `--skip` to leave tools out, `--histogram` for the live heap).

## Packages

| Package | Role |
|---|---|
| `write` | the `edit` tool: text edits of declarations, rename, verification, atomic writes, journal, policy and gate |
| `lang`, `lang.kotlin`, `lang.java` | file → facts (declarations, imports, references) via the Kotlin compiler's PSI (Kotlin and Java) |
| `index` | SQLite store, base build from git objects, build worker entry point, parse worker (`ParseWorker`) and its client |
| `repo` | repositories and worktrees → base index, base syncs, child-process builds |
| `overlay` | per-worktree overlays: change checks, refreshes, cleanup of removed worktrees |
| `changes` | a worktree's declarations compared with the merge-base: matching, line diffs, callers and tests |
| `taskcode` | `task_code`: history of the default branch by task id, changed declarations per landing, touch-set prediction from issue text |
| `query` | read view (with worktree overlays), `find` / `outline` / `symbol` |
| `query.usages` | resolver for references: scopes, receivers, type specs; `usages` / `calls` / `hierarchy` |
| `tracker`, `tracker.youtrack`, `tracker.mirror`, `tracker.read` | tracker adapter (YouTrack REST), SQLite mirror and watcher, `issue` / `tasks` / `similar` answers |
| `secrets` | the encrypted vault, its key protectors (DPAPI, Keychain, libsecret, passphrase), `env run`, the `/env/values` route |
| `compress` | `run`: output families (git status / log / diff, Gradle, test runners, generic) that shorten a command's output and keep every error line |
| `doc` | documents as sections with handles: digest, outline, section and line-window fetch, hash and the per-caller delta memory behind `doc` and `task_context` |
| `tools` | the tool catalog shared by MCP, HTTP API and CLI |
| `daemon` | Ktor server, MCP endpoint, job queue, call log |
| `jobs` | commands run for agents: policy hook, slots, processes, summaries, completion actions, `job` tool |
| `workspace` | `GET /workspaces`: worktrees, branches, tasks, merge and tracker state, orphan directories |
| `reconcile` | `GET /reconcile`, `POST /reconcile/run`: policy, executor, backoff state, scheduler, journal |
| `processes` | `GET /processes`: processes by workspace directory, memory per workspace, stopping the build tools of released workspaces |
| `docker` | Docker Engine API client (named pipe / unix socket), ownership labels, compose override, `GET /resources`: owned / adopted / unowned |
| `events` | event log, server-sent-events stream, webhook subscriptions and deliveries |
| `cli` | `codeloupe` commands and the daemon client |

`ParityTest` compares every tool answer with golden output of the Node.js prototype (phase 1); the
TerrioImporter part runs where that repository is checked out (`CODELOUPE_TERRIO`). `UsagesGoldenTest` checks
`usages` on 44 TerrioImporter symbols against a manually verified oracle (`src/test/resources/golden`) and writes
`build/reports/codeloupe/golden-usages.md`: superset of `rg -w`, precision of `exact` (≥ 95 %), candidate share.

## Documents in the repository

| Where | What |
|---|---|
| `docs/wiki/` | this wiki, one file per page; changed with the code and published by `tools/publish-wiki.mjs` (see below) |
| [docs/plan.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/plan.md) | the working plan: decisions, measurements, results per card (Czech) |
| [docs/ui-spec.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/ui-spec.md) | the desktop app's specification (Czech); [docs/design-revamp.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/design-revamp.md) its visual layer |
| [docs/benchmarks.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/benchmarks.md) | the generated benchmark report; [docs/context-audit.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/context-audit.md), [docs/analysis.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/analysis.md), [docs/lazy-edit.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/lazy-edit.md) the measurements behind the design (Czech) |
| [docs/ci.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/ci.md), [docs/code-signing.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/code-signing.md) | decision records with their numbers: CI cost, signing |
| [app/README.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/app/README.md) | the desktop app: running it, settings, security model, memory |
| [plugin/](https://github.com/Terrio-cz/CodeLoupe/blob/main/plugin) | the Claude Code plugin: MCP entry, hook, skill |

## The wiki

The wiki is generated from `docs/wiki/`; edit the files there in a normal change, never in the GitHub wiki editor (the next
publish would overwrite it). Pages are named `Page-Name.md`; link to another page as `[text](Page-Name)` or
`[text](Page-Name#heading-anchor)`, and to a file of the repository with its full
`https://github.com/Terrio-cz/CodeLoupe/blob/main/...` URL (a relative path does not work inside a wiki). `_Sidebar.md` and
`_Footer.md` are the navigation. `node tools/check-wiki-links.mjs` checks every link, anchor and image of the wiki and the
wiki links of the README, and CI runs it. On a push to `main` that touches `docs/wiki/**`, the `Wiki` workflow runs
`node tools/publish-wiki.mjs`, which mirrors the folder into the wiki's own git repository (a page you delete is deleted
there).

## Contributing

Issues and pull requests are welcome on [GitHub](https://github.com/Terrio-cz/CodeLoupe). Keep changes small and
tested: run the suites above, add a test next to the code (`src/test/kotlin`, fixtures through `TestRepos`), and update the
wiki page that describes what you change. The project is source-available under the
[PolyForm Noncommercial License 1.0.0](https://github.com/Terrio-cz/CodeLoupe/blob/main/LICENSE).
