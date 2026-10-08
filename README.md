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

git ≥ 2.31 and either the [bundle](#bundle) (it carries its own Java runtime) or, to build from source, JDK 25
(Gradle finds or downloads it as a toolchain).

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
build/install/codeloupe/bin/codeloupe task_code ABC-5            # its landed or predicted code
build/install/codeloupe/bin/codeloupe code_tasks OrderService.handle   # the tasks that touched it
build/install/codeloupe/bin/codeloupe status
```

The first query in a repository builds its index (seconds); later queries take milliseconds. The
daemon starts on the first CLI call; `codeloupe start` / `stop` manage it explicitly.

A CLI call against a running daemon takes ~0.2 s. The start script keeps a JVM class-data archive in
`<home>/cds/` (about 8 MB per install and build); the first call after an install creates it (~1.5 s), and it is
simply not used when that directory is not writable. `JAVA_OPTS` / `CODELOUPE_OPTS` add JVM flags.

### Claude Code

Two ways to connect, both ending in the same MCP server (`http://127.0.0.1:47391/mcp`, header `x-codeloupe: 1`, no secret).
`codeloupe` must be on `PATH` (the [bundle](#bundle)'s `bin/`) for the session hook; without it the plugin still works
while the daemon runs (tray app, `codeloupe start`).

**1. Plugin (recommended)** — MCP server, a skill saying which tool to use when, and a `SessionStart` hook that runs
`codeloupe start`, so the daemon is up with the session. The repository is its own marketplace:

```bash
claude plugin marketplace add Terrio-cz/CodeLoupe      # or the path of a checkout / the app's claude-plugin folder
claude plugin install codeloupe@codeloupe              # --scope user (default) | project | local
```

The plugin lives in [plugin/](plugin/) (`.claude-plugin/plugin.json`, `.mcp.json`, `hooks/`, `skills/codeloupe/`) and the
marketplace manifest in [.claude-plugin/marketplace.json](.claude-plugin/marketplace.json). Check changes with
`claude plugin validate plugin --strict` and `claude plugin validate .`; try it for one session without installing:
`claude --plugin-dir plugin`. Hook settings: `CODELOUPE_BIN` (launcher if not on `PATH`), `CODELOUPE_HOOK_VERBOSE=1`
(print why it did nothing). The hook needs `bash` (Git Bash on Windows, which Claude Code uses anyway) and never fails a session.

**2. MCP entry only** — no skill, no autostart:

```bash
claude mcp add --transport http --scope user codeloupe http://127.0.0.1:47391/mcp --header "x-codeloupe: 1"
```

**From the desktop app**: Settings → *Claude Code* shows whether `claude` is found and what is connected, and the
buttons *Připojit plugin…* and *Přidat jen MCP server…* run exactly the commands above (after a native confirmation that
lists them, with the daemon's current port), through the `claude` CLI, so Claude Code writes its own configuration.
Without `claude` on `PATH` the card shows the commands to run by hand. The plugin is added from the marketplace folder
next to the app (`resources/claude-plugin` when packaged, `CODELOUPE_PLUGIN_DIR` to override, the repository root in a
development run); the installers ship `.claude-plugin/marketplace.json` and `plugin/` there. The plugin's session hook
looks for `codeloupe` on `PATH`; an installed app does not put it there, so set `CODELOUPE_BIN` to
`<install>/resources/codeloupe/bin/codeloupe` (`.bat` on Windows) or add that directory to `PATH`.

A daemon on another port: set `CODELOUPE_PORT` for it and for Claude Code (the plugin's URL reads it); for the MCP entry
the app writes the port it watches, and `codeloupe mcp-config` prints the entry for the configured one.

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
| `task_code` | links between tasks and code, from the default branch's history (works without a tracker). `query` = a task id (`TER-5`): its landing commit, files and changed declarations (`+ ~ ^ -`), the worktree whose branch names it, and for an open task the touch set predicted from its text — `=` sure · `~` likely · `?` guess · `+` new file, each with the issue text it comes from. `query` = a declaration (`Type.member`) or a file path: the tasks that changed it, newest first, with landing commits (`code_tasks <symbol\|path>` on the command line), plus open tasks whose text points at it. Task ids follow the tracker projects, or `taskPattern` (a regular expression) in `.codeloupe.json` |

With a tracker configured (see Configuration) three more tools work on a local mirror of its projects:

| Tool | Returns |
|---|---|
| `issue` | one issue as compact markdown: `view=brief` (fields, links, criteria checklist, section index), `full`, or `sections=[…]` (description headings by prefix, `criteria`, `fields`, `links`, `comments`, `attachments`, `history`). A second read from the same `root` answers `unchanged since …` or only what changed; `since=<ISO time>` diffs against that time, `since=none` shows it again |
| `tasks` | one line per task (`id state · type · priority ‹epic› title ⛔blockers`). `mode=list` with a YouTrack-like `query` (`project: TER state: -Done #unresolved epic: TER-1 type: Bug {Fix versions}: 1.0 sort: id` plus full-text words), `graph` (an issue's epic, dependencies, subtasks, relations; `depth` ≤ 3), `ready` (open tasks without open subtasks whose dependencies are resolved and that no git worktree branch holds), `progress` (an epic: counts by state, criteria, blockers, open tasks) |
| `update` | writes to the tracker: `set={Field: value}` (State, Assignee, Priority, Type, `summary`, `description` or any custom field; comma-separated for multi-value fields; an empty value clears) and/or `comment=<text>`. Answers one line of at most 300 characters — the fields that changed (`State: To do→Done`), `+comment <id>`, and the state when it did not change — instead of the issue. The mirror stores the tracker's own answer to the write, so the next `issue` read needs no request |

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

## Workspaces

`codeloupe workspaces [--repo <path>] [--state orphan] [--size] [--json]` and `GET /workspaces?repo=&size=1` (JSON, for the
app) list every worktree of the configured repositories: role, branch, task id (from the branch name, else the directory
name, by the repository's task pattern), commits ahead of the default branch, the task's state from the tracker mirror,
last activity (newer of the HEAD commit and the last git operation in the worktree) and, with `size`, disk size.
State: `active`; `landed` (everything is on the default branch and the task is resolved or has commits there);
`abandoned` (unmerged work, idle for more than `abandonedDays`); `orphan` (a directory under a worktree root git has no
worktree for, or a worktree whose directory is gone). Nothing is removed: orphans are only reported. Git is read in-process,
no `git` process runs. The first call in a repository waits for the scan of its history (`task_code` shares it).

```json
{ "workspaces": { "abandonedDays": 14, "repos": [ { "path": "C:/ws/Terrio", "roots": ["C:/ws/terrio-worktrees"] } ] } }
```

Repositories also come from a tracker's `repos` and from the repositories the daemon has served; `<repo name>-worktrees`
beside a repository is always a root.

### Docker resources

Every container, image, volume and network that is made through CodeLoupe carries three labels naming its owner:
`codeloupe.repo` (the repository's main worktree), `codeloupe.workspace` (the worktree directory) and `codeloupe.task`
(the workspace's task id, empty for one without). The workspace is the one of the directory you run in (`--dir` names
another), found through the registry above.

```
codeloupe ws up [-f compose.yaml] [-p project] [--profile x] [-- up-args]   # default: -d
codeloupe ws run [--dir d] <docker run arguments>
codeloupe ws build [--dir d] <docker build arguments>
codeloupe ws volume create <name>
codeloupe ws resources [--class owned|adopted|unowned] [--json]     # GET /resources
```

`ws volume create` and the inventory use the Docker Engine API (named pipe `\\.\pipe\dockerDesktopLinuxEngine` /
`docker_engine`, or a unix socket; `DOCKER_HOST` with `npipe://` or `unix://` is honoured): no `docker` process, no
output parsing. Compose, build and the full `docker run` command line are client side, so those three call `docker`
with the labels added and check the result through the API: `ws up` reads `docker compose config --format json` and adds
the labels through a generated override file to every service (containers), to `build` (images the project builds),
and to the project's own volumes and networks (not to `external` ones); `ws build` passes `--label` and verifies the
image; `ws run` passes `--label` and first creates the named volumes it mounts, labelled (Docker would create them
without). A `codeloupe.*` label given by the caller is refused, a volume that exists and is not the workspace's is
never relabelled. Exit code 3: something the command made came out without the labels. Images a project only pulls
are not created by CodeLoupe and carry no labels.

`ws resources` lists what exists, by owner: **owned** (the labels), **adopted** (an adoption rule of the config maps its
name to a workspace, for resources made before the labels existed) and **unowned**, which is only reported — nothing
in CodeLoupe changes a resource it does not own, and adoption itself changes nothing in Docker: it is this mapping.
Owned and adopted rows show the workspace's state in the registry (`not in registry` when its worktree is gone).

```json
{ "workspaces": { "adoption": [
  { "repo": "TerrioImporter", "match": "^terrio-ter-(\\d+)(?:[-_].*)?$", "workspace": "TER-$1", "task": "TER-$1" },
  { "repo": "TerrioImporter", "match": "^(?:terrio-)?importer-app:ter-(\\d+)(?:-.*)?$", "kinds": ["image"], "workspace": "TER-$1", "task": "TER-$1" }
] } }
```

`match` is a case-insensitive regular expression tried against each name of the resource (container name, image
`repo:tag`, volume or network name) and against the compose project it belongs to; `$1`… stand for its groups. Rules are
tried in order, the first one wins, labels beat rules. Containers, volumes and networks are also matched by their compose
project. Images are not, by default: compose labels an image with the project that built it, but images get re-tagged and
shared between tasks (`aot`, `jdk25`), so the project alone does not make one a task's leftover. A rule with
`"matchProject": true` (and `"kinds": ["image"]`) adopts the untagged and re-tagged images a stack built; the `via`
column says which name matched.

### Cleanup of released workspaces (reconciler)

`codeloupe ws reconcile` is the dry run (`GET /reconcile`): for every owned or adopted resource and every orphan
directory it says what the policy does and why. Unowned resources are not in it at all.

| verdict | when | what happens |
|---|---|---|
| `auto` | labelled by CodeLoupe, its workspace has **landed**, no container of the workspace runs, older than `graceMinutes` | removed without asking, if `auto` is on |
| `confirm` | adopted by a rule; or the workspace is abandoned, an orphan, or gone from the registry; or landed but still running; or an orphan directory under a worktree root | removed only when named: `ws reconcile --confirm <key>` or `--workspace TER-420` |
| `keep` | the workspace is active; its repository is not in the registry; younger than the grace period | stays |
| `protected` | a `protect` rule of the config matches | never touched, whatever else holds |

`--run` (or `POST /reconcile/run` with `{"confirm": [keys], "workspaces": [names]}`) does it now: the `auto` entries plus
what is named. A named `keep` or `protected` entry is refused. The plan is re-read from the registry and Docker for every
run, so a stale key removes nothing it should not. Removal goes through the Engine API, containers first (stopped, removed
with their anonymous volumes), then networks, volumes, images, never forced: a resource that is in use is *blocked*, not
killed. An orphan directory is deleted without following links; a file that is still locked (Windows) leaves it blocked.

Blocked and failed targets are retried with a growing wait (`retryBaseMinutes`, doubling up to `retryMaxMinutes`), kept
in `<home>/reconcile-state.json`, so the backoff survives a restart of the daemon or the PC. A removal someone confirmed
is retried without a second confirmation. With `auto` on the daemon runs the `auto` entries shortly after it starts, after
a job finished, every `intervalMinutes` while a client has called the daemon in the last 15 minutes, and whenever a retry
falls due. Every attempt is written to `daemon.log` and `<home>/reconcile.jsonl` and emitted as a `reconcile.action` event.

```json
{ "workspaces": { "reconcile": { "auto": true, "intervalMinutes": 30, "graceMinutes": 60, "retryBaseMinutes": 1, "retryMaxMinutes": 360,
  "protect": [ { "match": "^terrio-importer(_|$)" }, { "match": "^terrio-importer_terrio-postgres-data$", "kinds": ["volume"] } ] } } }
```

`auto` is off by default. `protect` patterns are regular expressions tried (case-insensitively, anywhere in the name,
so anchor them) against each name of a resource and its compose project; without `kinds` they also cover directories.

## Desktop app

`app/` holds the Electron desktop app (tray, notifications, daemon start/stop, screens over the daemon's
read-only UI API). See [app/README.md](app/README.md) and the UI spec [docs/ui-spec.md](docs/ui-spec.md).

## Configuration

| | Default | Override |
|---|---|---|
| State and indexes | `%LOCALAPPDATA%\codeloupe`, `~/Library/Caches/codeloupe`, `$XDG_CACHE_HOME/codeloupe` | `CODELOUPE_HOME` |
| Port | 47391 | `CODELOUPE_PORT` or `<home>/config.json` `{ "port": … }`. A daemon with another `CODELOUPE_HOME` refuses 47391 and the port configured in the default home: it needs a port of its own (MCP clients find the daemon by port alone). |
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
last sync checks that one issue's `updated` first. The mirror only reads; `update` is the one way CodeLoupe writes to the tracker (YouTrack: field values, `summary`, `description`, comments), with the token's own permissions. `repos` are git repositories whose worktree
branch names (`ABC-5`, `feature/ABC-5-x`) mark tasks as taken for `tasks mode=ready`, besides every repository the
daemon has indexed.

The daemon listens on 127.0.0.1 only and refuses requests with a foreign `Host`, any `Origin`, or
without the `x-codeloupe` header; responses carry `Connection: close`. Calls are logged (tool, latency,
size — no content) to `<home>/calls.jsonl`, the daemon to `<home>/daemon.log`.

## Bundle

```bash
./gradlew bundle     # build/distributions/codeloupe-<version>-<os>-<arch>.zip
```

The zip holds `bin/` (launchers), `lib/` (jars) and `runtime/`, a jlink runtime with only the modules the jars use
(found by `jdeps`) plus the ones needed at run time. The launchers prefer `runtime/` to any JDK on the machine, so the
bundle runs without Java. jlink output runs only on the OS it was built on, so CI builds one bundle per OS
(`bundle` job in [ci.yml](.github/workflows/ci.yml)); the Electron installer takes the same directory (see [Installers](#installers)).
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
(electron-builder, config in [app/electron-builder.yml](app/electron-builder.yml)). The CPU is the one the build runs
on, because the runtime is. CI builds them in the `bundle` job and keeps them for 7 days as `installer-<os>` artifacts.

| OS | Installer | Notes |
|---|---|---|
| Windows x64 | `CodeLoupe-<v>-win-x64.exe` (NSIS, per user, one click) | Starts the app when it ends. An update or uninstall first stops the installation's own daemon. The uninstaller asks whether to delete the data (`%LOCALAPPDATA%\codeloupe`, `%APPDATA%\codeloupe-desktop`); `/S` and updates keep it. |
| macOS arm64 | `CodeLoupe-<v>-mac-arm64.dmg` | Drag to Applications. Removing the app leaves the data in `~/Library/Caches/codeloupe` and `~/Library/Application Support/codeloupe-desktop` until it is deleted by hand. |
| Linux x64 | `CodeLoupe-<v>-linux-x64.AppImage`, `.deb` | The AppImage copies the bundle to `<userData>/daemon/<version>` once, because the daemon outlives its mount. Removing the app leaves the data in `~/.cache/codeloupe` and `~/.config/codeloupe-desktop`. |

An installed app reads real data (`apiSource: daemon`) and starts the daemon from its own runtime; the CLI command in
Settings stays on its default and is resolved at start-up, so an update never leaves a stale path. Not yet: signing
and notarisation (CL-105, until then Windows shows an unknown publisher and macOS refuses the app), the release
pipeline (CL-106), auto-update (CL-107), installer smoke tests that install and start the app (CL-108; CI only
unpacks each installer and runs the bundle inside), macOS x64.

## Develop

```bash
./gradlew test
```

`node tools/profile.mjs --cli build/install/codeloupe/bin/codeloupe --home <tmp> --root <repo> --worktree <worktree>`
profiles a warm query, the first query in a worktree and (with `--clone`) an overlay refresh: client latency split
by the daemon's own timings (`/status` `timings`, `gitSpawns`) into git, worktree walk, SQL, the rest of the tool and HTTP.

| Package | Role |
|---|---|
| `lang`, `lang.kotlin` | file → facts (declarations, imports, references) via Kotlin PSI |
| `index` | SQLite store, base build from git objects, build worker entry point |
| `repo` | repositories and worktrees → base index, base syncs, child-process builds |
| `overlay` | per-worktree overlays: change checks, refreshes, cleanup of removed worktrees |
| `changes` | a worktree's declarations compared with the merge-base: matching, line diffs, callers and tests |
| `taskcode` | `task_code`: history of the default branch by task id, changed declarations per landing, touch-set prediction from issue text |
| `query` | read view (with worktree overlays), `find` / `outline` / `symbol` |
| `query.usages` | resolver for references: scopes, receivers, type specs; `usages` / `calls` / `hierarchy` |
| `tracker`, `tracker.youtrack`, `tracker.mirror`, `tracker.read` | tracker adapter (YouTrack REST), SQLite mirror and watcher, `issue` / `tasks` answers |
| `tools` | the tool catalog shared by MCP, HTTP API and CLI |
| `daemon` | Ktor server, MCP endpoint, job queue, call log |
| `jobs` | commands run for agents: policy hook, slots, processes, summaries, completion actions, `job` tool |
| `workspace` | `GET /workspaces`: worktrees, branches, tasks, merge and tracker state, orphan directories |
| `reconcile` | `GET /reconcile`, `POST /reconcile/run`: policy, executor, backoff state, scheduler, journal |
| `docker` | Docker Engine API client (named pipe / unix socket), ownership labels, compose override, `GET /resources`: owned / adopted / unowned |
| `events` | event log, server-sent-events stream, webhook subscriptions and deliveries |
| `cli` | `codeloupe` commands and the daemon client |

`ParityTest` compares every tool answer with golden output of the Node.js prototype (phase 1); the
TerrioImporter part runs where that repository is checked out (`CODELOUPE_TERRIO`). `UsagesGoldenTest` checks
`usages` on 44 TerrioImporter symbols against a manually verified oracle (`src/test/resources/golden`) and writes
`build/reports/codeloupe/golden-usages.md`: superset of `rg -w`, precision of `exact` (≥ 95 %), candidate share.

## Licence

CodeLoupe is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE): you may use, study, modify
and share it for any noncommercial purpose (personal use, research, education, charities, public institutions), but
not sell it, offer it as a paid product or service, or use it for commercial purposes. This is not an open-source
licence in the OSI sense. For commercial use, contact the licensor (Terrio-cz).
