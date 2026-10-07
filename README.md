# CodeLoupe

On-demand code index for AI coding agents. Ask for a declaration, a file outline, its usages, callers or type
hierarchy, or what a branch changed, and get exactly that piece of code instead of grepping and reading whole files.
With a tracker configured it also mirrors your issues (YouTrack first) and answers issue reads and task queries locally.

- **Any git repository**, no configuration: the base index follows the default branch and is built
  from git objects; every worktree of the repository shares it and adds an overlay of its own changed, new
  and deleted files, checked when a query arrives (no file watchers, no CPU while idle).
- **No IDE**: the Kotlin compiler's own parser (syntax only, no classpath) and SQLite.
- **One daemon per machine** for every agent window, started on demand; heavy builds run one at a time
  in a short-lived child JVM at low priority, so the daemon stays small (140–190 MB).
- **MCP** over Streamable HTTP (stateless) plus the same tools on a CLI.

Languages: Kotlin (Java next). Status and roadmap: [docs/plan.md](docs/plan.md) (Czech).

## Requirements

JDK 25 (Gradle finds or downloads it as a toolchain; a bundled runtime is planned), git ≥ 2.31.

## Use

```bash
./gradlew installDist
build/install/codeloupe/bin/codeloupe outline OrderService          # from inside a repository
build/install/codeloupe/bin/codeloupe symbol "OrderService.handle(_)"
build/install/codeloupe/bin/codeloupe find "*Repository" --kind interface
build/install/codeloupe/bin/codeloupe usages OrderService.handle
build/install/codeloupe/bin/codeloupe calls OrderService.handle --depth 2      # --callees for what it calls
build/install/codeloupe/bin/codeloupe hierarchy Repository
build/install/codeloupe/bin/codeloupe issue ABC-5 --section scope   # with a tracker configured
build/install/codeloupe/bin/codeloupe tasks "epic: ABC-1" --mode ready
build/install/codeloupe/bin/codeloupe status
```

The first query in a repository builds its index (seconds); later queries take milliseconds. The
daemon starts on the first CLI call; `codeloupe start` / `stop` manage it explicitly.

### Claude Code

`codeloupe mcp-config` prints the `.mcp.json` entry:

```json
{ "codeloupe": { "type": "http", "url": "http://127.0.0.1:47391/mcp", "headers": { "x-codeloupe": "1" } } }
```

Tools take `root` — the absolute path of the repository or worktree to answer for.

| Tool | Returns |
|---|---|
| `find` | declarations by name, `Type.member` or glob: `path:lines [container] signature` |
| `outline` | members of a file or type with line ranges, no bodies |
| `symbol` | one declaration's source (KDoc, annotations, body) by `Type.member`, `member(ParamType)`, `pkg.Type` or `File.kt:line`; large types collapse to header + members |
| `usages` | every reference to a declaration, grouped by file and enclosing declaration, one code line each, `=` exact or `?` candidate; a superset of what `rg -w` finds in code, references that resolve elsewhere only counted (`all=true` lists them) |
| `calls` | callers (default) or callees as a tree, depth ≤ 3; below the first level only exact links |
| `hierarchy` | supertypes and subtypes of a type (object expressions included, and lambdas converted to a `fun interface`), or what a member overrides and what overrides it |
| `job` | start a long command in the daemon (status, cancel); see [Jobs and events](#jobs-and-events); takes `cwd`, not `root` |
| `changes` | what the worktree changed against the merge-base with the default branch (committed and uncommitted), by declaration: `+` added, `~` body changed, `^` signature changed (with the old one), `-` removed; each with its callers and tests; `bodies=true` adds a line diff per declaration |

With a tracker configured (see Configuration) two more tools answer from a local mirror of its projects:

| Tool | Returns |
|---|---|
| `issue` | one issue as compact markdown: `view=brief` (fields, links, criteria checklist, section index), `full`, or `sections=[…]` (description headings by prefix, `criteria`, `fields`, `links`, `comments`, `attachments`, `history`). A second read from the same `root` answers `unchanged since …` or only what changed; `since=<ISO time>` diffs against that time, `since=none` shows it again |
| `tasks` | one line per task (`id state · type · priority ‹epic› title ⛔blockers`). `mode=list` with a YouTrack-like `query` (`project: TER state: -Done #unresolved epic: TER-1 type: Bug {Fix versions}: 1.0 sort: id` plus full-text words), `graph` (an issue's epic, dependencies, subtasks, relations; `depth` ≤ 3), `ready` (open tasks without open subtasks whose dependencies are resolved and that no git worktree branch holds), `progress` (an epic: counts by state, criteria, blockers, open tasks) |

Usages are resolved without an IDE or compiler: the scopes, imports and aliases a file sees, the receiver's
type where syntax tells it (declared types, `Type(…)`, what a call returns, collection elements in lambdas), and
overloads by argument count. Unsure hits are marked, never dropped. One heuristic: on a receiver of unknown type,
a name the index declares only once (and no library declares, judging by the core API and the files' imports) is
taken as exact. A name that matches several unrelated declarations must be qualified (`Type.member`).

## Jobs and events

Long commands (tests, builds, deploys) run in the daemon instead of in an agent's turn: the agent starts a job, ends
its turn, and is woken once when it ends — or not at all when the follow-up is deterministic.

```bash
codeloupe job start --slot gradle-test -- ./gradlew test     # prints the id at once
codeloupe job wait J261007-142233-x7k2                       # as a background task: blocks, no time limit
codeloupe job start --wait -- node run/build.mjs              # both in one call
codeloupe job status [id]  ·  codeloupe job cancel <id>
```

- **Detached**: a job is a child of the daemon, not of the agent session; it outlives the turn and the session. On
  Windows the daemon is started outside the caller's job object (headless `claude -p` runs kill theirs at turn end),
  and jobs run in the daemon's own kill-on-close job object: if the daemon dies they end too, never as orphans.
- Output and errors go straight to `<home>/jobs/<id>.log`; the record (`<home>/jobs.db`) keeps exit code, duration and a
  **compact summary**: test counts (Gradle, Maven, node:test, Jest, pytest), the first failure lines, the last 15 lines.
- **Slots**: `--slot <name>` holds a named resource while running; a busy slot queues the job in the daemon (first come,
  first served). `config.json` `slots` sets capacities (`{ "gradle-test": 2 }`); a slot not listed holds one job.
- **Policy**: commands run by the daemon never pass the agent host's guard hooks, so every job — follow-ups included,
  and again after a slot wait — is first fed to `policyHook` exactly as Claude Code feeds a PreToolUse hook for a Bash
  call (`tool_name` `Bash`, `tool_input.command` = the argv quoted for Bash with `--env` values as `K=v` prefixes, `cwd`).
  Only an allow (or no output) starts it; deny, ask, exit 2, a crash, a timeout or unreadable output refuse it (exit 2,
  no record). The hook runs in the daemon's environment, so a caller cannot redirect it with variables of its own; and
  the daemon's environment is not its starter's: on Windows it is the user's own (Win32_Process.Create), elsewhere the
  starter's cut to `HOME`, `USER`, `PATH`, `LANG`, … Protect `config.json` like any other policy file; the CLI refuses a
  daemon on its port whose home is not its own.
- No shell: the command is an argv (`bash -c '…'` for pipes). On Windows `./gradlew` or `npm` resolve to their
  `.bat`/`.cmd` like in Bash (a bare name only on `PATH`); a batch file refuses arguments holding `& | < > ^ % ! " ( )`,
  which cmd.exe would parse again. The job gets the daemon's environment plus `--env K=V` (values never stored or
  emitted).
- **Wait once**: `job wait` long-polls the daemon (5 min per request, re-sent, across daemon restarts) until the job and
  its follow-ups end, prints the compact report and exits with the job's code. A restarted daemon reports queued and
  running jobs as `lost`, keeps their logs and ends what survived of them. `codeloupe stop` refuses while jobs run
  (`--force` ends them). `job cancel <id>` ends the live job of the chain and its whole process tree; after a cancel no
  job steps run, `notify` and `webhook` steps still do.
- **Completion actions** (`--then` after success, `--on-failure` after a failure, in order): a closed set of typed
  steps, never a shell string — `job[@slot]:<command line>` (a follow-up job; the steps after it continue when it ends),
  `notify[:message]` (a `job.notify` event that wakes the agent), `webhook:<url>` (the `job.finished` event to a URL).
  Any step can carry a condition on exit code and summary: `failed==0 && tests>0 ? notify:green` (fields `exit`,
  `tests`, `passed`, `failed`, `skipped`, `seconds`; a count the log did not report never matches).
- **Wake**: the last `job.finished` of a chain carries `wake` — by default true only when something failed if the job
  declared `--then` steps, else always (`--wake always|failure|never`). A launcher subscribed to `job.finished` /
  `job.notify` resumes a headless session only when `wake` is true; `--tag` (default `CODELOUPE_JOB_TAG`) tells it which.
- MCP: one tool, `job` (`action` start / status / cancel); waiting is the CLI's job.

**Events**: `job.started`, `job.finished`, `job.notify`, `build.done`, `overlay.refreshed`, numbered (`seq`) and kept in
`<home>/events.db`, scrubbed of secret-looking values (tokens, passwords, `Authorization`, URL credentials) before they are
stored. `GET /events?since=<seq>`; `GET /events/stream` is a server-sent-events stream that resumes after `Last-Event-ID`.
**Webhooks**: `codeloupe webhook add <url> [--event job.*]` persists a subscription; each delivery is a JSON POST signed with
`x-codeloupe-signature: sha256=HMAC(<home>/webhook.key, "<x-codeloupe-timestamp>.<body>")`, retried after 2 s, 10 s,
1 min, 5 min and 30 min (also after a daemon restart), logged (`webhook deliveries`). Targets are this machine only, any
port but the daemon's, unless `remoteWebhooks` lists the https origin; redirects are not followed.

## Desktop app

`app/` holds the Electron desktop app (tray, notifications, daemon start/stop, screens over the daemon's
read-only UI API). See [app/README.md](app/README.md) and the UI spec [docs/ui-spec.md](docs/ui-spec.md).

## Configuration

| | Default | Override |
|---|---|---|
| State and indexes | `%LOCALAPPDATA%\codeloupe`, `~/Library/Caches/codeloupe`, `$XDG_CACHE_HOME/codeloupe` | `CODELOUPE_HOME` |
| Port | 47391 | `CODELOUPE_PORT` or `<home>/config.json` `{ "port": … }` |
| Default root for tools without `root` | — | `CODELOUPE_ROOT` or `config.json` `defaultRoot` |
| Base branch of a repository | `origin/HEAD`, else `origin/main`, `origin/master`, `main`, `master` | `.codeloupe.json` `{ "baseBranch": "origin/master" }` in the main worktree |
| Build worker heap, timeouts | 512 MB, query wait 10 s, build 10 min | `config.json` `buildHeapMb`, `queryTimeoutMs`, `buildTimeoutMs` |
| Reuse of a worktree check | 1 s: queries within a second of the last check of their worktree share it | `config.json` `overlayCheckMs` (1 = check on every query) |
| Job slots | any name, one job each | `config.json` `slots` `{ "gradle-test": 2, "vps-test": 1 }` |
| Policy for jobs | none (every command allowed) | `config.json` `policyHook` — argv of a PreToolUse hook, e.g. `["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "C:/ws/.claude/hooks/guard.ps1"]`; `policyTimeoutMs` (30 s) |
| Remote webhook targets | none (local only) | `config.json` `remoteWebhooks` `["https://hooks.example.com"]` |
| Trackers to mirror | none | `config.json` `trackers` (below) |
| Tracker sync while clients are active, idle stop | every 3 min; stops 10 min after the last tool call | `config.json` `trackerSyncMinutes`, `trackerIdleMinutes` |

```json
{
  "trackers": [{
    "name": "acme", "type": "youtrack", "url": "https://acme.youtrack.cloud", "projects": ["ABC", "OPS"],
    "token": { "env": "YOUTRACK_TOKEN" },
    "repos": ["C:/src/acme"]
  }]
}
```

The token comes from an environment variable of the daemon (`{ "env": "NAME" }`; on Windows the user's persistent
variables, not the shell that happened to start it — see [Jobs and events](#jobs-and-events)) or a `KEY=value` file (`{ "dotenv": "<path>",
"key": "NAME" }`), read by the daemon on each request; it never appears in answers, errors, `/status` or logs. The
mirror (`<home>/trackers/<name>.db`, SQLite + FTS5) loads each project once, then a watcher asks only for issues whose
`updated` moved — and only while tool calls arrive: no client, no polling. A read more than 30 s after the project's
last sync checks that one issue's `updated` first. Mirroring is read-only. `repos` are git repositories whose worktree
branch names (`ABC-5`, `feature/ABC-5-x`) mark tasks as taken for `tasks mode=ready`, besides every repository the
daemon has indexed.

The daemon listens on 127.0.0.1 only and refuses requests with a foreign `Host`, any `Origin`, or
without the `x-codeloupe` header; responses carry `Connection: close`. Calls are logged (tool, latency,
size — no content) to `<home>/calls.jsonl`, the daemon to `<home>/daemon.log`.

## Develop

```bash
./gradlew test
```

| Package | Role |
|---|---|
| `lang`, `lang.kotlin` | file → facts (declarations, imports, references) via Kotlin PSI |
| `index` | SQLite store, base build from git objects, build worker entry point |
| `repo` | repositories and worktrees → base index, base syncs, child-process builds |
| `overlay` | per-worktree overlays: change checks, refreshes, cleanup of removed worktrees |
| `changes` | a worktree's declarations compared with the merge-base: matching, line diffs, callers and tests |
| `query` | read view (with worktree overlays), `find` / `outline` / `symbol` |
| `query.usages` | resolver for references: scopes, receivers, type specs; `usages` / `calls` / `hierarchy` |
| `tracker`, `tracker.youtrack`, `tracker.mirror`, `tracker.read` | tracker adapter (YouTrack REST), SQLite mirror and watcher, `issue` / `tasks` answers |
| `tools` | the tool catalog shared by MCP, HTTP API and CLI |
| `daemon` | Ktor server, MCP endpoint, job queue, call log |
| `jobs` | commands run for agents: policy hook, slots, processes, summaries, completion actions, `job` tool |
| `events` | event log, server-sent-events stream, webhook subscriptions and deliveries |
| `cli` | `codeloupe` commands and the daemon client |

`ParityTest` compares every tool answer with golden output of the Node.js prototype (phase 1); the
TerrioImporter part runs where that repository is checked out (`CODELOUPE_TERRIO`). `UsagesGoldenTest` checks
`usages` on 44 TerrioImporter symbols against a manually verified oracle (`src/test/resources/golden`) and writes
`build/reports/codeloupe/golden-usages.md`: superset of `rg -w`, precision of `exact` (≥ 95 %), candidate share.
