How CodeLoupe answers: one small background process that keeps a per-file fact index in SQLite and composes answers from it
when a question arrives. This page is the model in the head; the numbers are measurements (see [Benchmarks](Benchmarks)).

## One daemon per machine

- **Started on demand**, by the first CLI call, `codeloupe start`, the Claude Code plugin's session hook or the desktop
  app. A second start finds the running daemon and does nothing. `codeloupe stop` ends it and writes `<home>/stopped` so
  that the desktop app does not start it again; `codeloupe start` removes the marker. `stop` refuses while jobs run
  (`--force` ends them).
- **Stateless MCP over HTTP** (Streamable HTTP) on `127.0.0.1:47391`, plus the same tools on the CLI. A restart of the
  daemon does not break an agent window: its next call simply reaches the new process. Only requests with the
  `x-codeloupe` header, no `Origin` and a local `Host` are served ([Configuration](Configuration#listening-logs-and-status)).
- **Idle costs nothing**: no file watchers and no timers that wake it. A worktree is checked when a query arrives, a
  tracker is polled only while tool calls arrive.
- **Small by design**: builds of an index run one at a time in a short-lived child JVM at low priority, so the daemon
  itself stays small. Heavy work never blocks a read.

| Measured | Value |
|---|---|
| Daemon, two repositories indexed | about 210 MB resident |
| Peak while indexing a 319-file repository | 517 MB |
| CPU while idle, 30 s | 0 ms |
| Warm call | 10-11 ms; 26 ms text search; about 750 ms `changes` of a branch |
| First index of a repository | 7.3 s (319 Kotlin files), 2.7 s (457 files of CodeLoupe) |
| Index on disk | 62 MB for both repositories |
| Idle daemon of a small bundle | 94-109 MB ([Packaging and releasing](Packaging-and-releasing#bundle)) |
| CLI call against a running daemon | about 0.2 s |

Budgets that make `/status` warn (`p95Ms`, `queueWaitMs`, `rssMb`, `busyRate`) are configurable
([Configuration](Configuration)).

## What the index holds

The index holds the facts of **one file** at a time and no global graph: per file its imports, its declarations (kind,
name, container, parameters, types, modifiers, annotations, supertypes, the line ranges of declaration, body and KDoc)
and its references (name, position, kind, the text of the receiver, the enclosing declaration). Relations across files
(who uses, who calls, subtypes) are composed when a question is asked, so changing a file means parsing that file again,
nothing more. Modules and their project dependencies come from `settings.gradle(.kts)` and `pom.xml`.

Kotlin and Java files are parsed by the Kotlin compiler's own parsers (syntax only, no classpath), which is why there is
no IDE and no build needed. The result is a SQLite database per repository base and one per worktree overlay, in the
daemon's home ([Worktrees and overlays](Worktrees-and-overlays)).

## How references are resolved

Usages are resolved without an IDE or compiler: the scopes, imports and aliases a file sees, the receiver's
type where syntax tells it (declared types, `Type(…)`, what a call returns, collection elements in lambdas), and
overloads by argument count. Unsure hits are marked, never dropped. One heuristic: on a receiver of unknown type,
a name the index declares only once (and no library declares, judging by the core API and the files' imports) is
taken as exact. A name that matches several unrelated declarations must be qualified (`Type.member`).

A hit is `exact` (only the target is possible) or `candidate` (it may be the target: a dispatch through a supertype, a
smart cast, a constructor argument); references that resolve to other declarations are counted and listed with
`all=true`. `usages` therefore answers a superset of `rg -w` over code; precision of `exact` is checked against a manually
verified oracle in the tests ([Development](Development)).

## The parse worker

The daemon does not parse in its own process: edited files (an overlay refresh, a small base sync, `task_code`) go to a
**parse worker**, a small child JVM (`index.ParseWorker`, one JSON line in, one out) that the daemon starts on the first
file to parse and that exits after `parseWorkerIdleSeconds` (default 300) without one, or when the daemon ends. The
compiler's parser is some 20 MB of classes, symbols and code plus the heap its syntax trees fill; held in the daemon it
would be resident for as long as the daemon lives. While the worker runs it holds about 100 MB of its own; a worker that
cannot start or dies is replaced once, and then the daemon parses itself (slower on memory, never wrong). With the worker
the daemon's heap limit is 64 MB, with `parseWorkerIdleSeconds` 0 it is 80.

## What it keeps in its home

| In `<home>` | What |
|---|---|
| `config.json` | the settings ([Configuration](Configuration)) |
| `daemon.json`, `daemon.log`, `calls.jsonl` | the running daemon's pid, port and version; its log; tool, latency and size of every call |
| base and overlay databases, `aot/` | the indexes ([Worktrees and overlays](Worktrees-and-overlays)); the JVM AOT caches of the CLI and the daemon |
| `jobs.db`, `jobs/`, `events.db` | jobs, their logs and the event log ([Jobs and events](Jobs-and-events)) |
| `trackers/` | tracker mirrors ([Trackers](Trackers)) |
| `secrets/` | the encrypted store and its audit ([Environment and secrets](Environment-and-secrets)) |
| `transcripts.db` | the ingested agent runs ([Metrics and savings](Metrics-and-savings)) |
| `writes.jsonl` | the journal of `edit` writes |
| `ports.json`, `releases.json`, `reconcile-state.json`, `reconcile.jsonl` | workspaces ([Workspaces and Docker cleanup](Workspaces-and-Docker-cleanup)) |
| `accounts.json` | Claude Code and YouTrack accounts ([Desktop app](Desktop-app#accounts)) |

Only the daemon writes the indexes; a new base goes to a new file and is switched in atomically.
