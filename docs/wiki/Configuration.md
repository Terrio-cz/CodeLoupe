Everything has a default; a daemon needs no configuration to answer. Settings live in `<home>/config.json` (read by the
daemon), in environment variables of the daemon, and, per
repository, in a `.codeloupe.json` in its main worktree (`baseBranch`, `taskPattern` and the `write` restrictions below;
a repository's file can add `write` restrictions but never enable writing).

## Repositories

`codeloupe repos add <folder>...` writes the folders (each must hold a `.git`) to `config.json` `workspaces.repos` without touching the rest of the file (a file that is not valid JSON is refused,
not rewritten) and asks the daemon a first question per repository so that it is known and its base index starts building; `repos list` prints what is configured, `--json` gives the report the
desktop app's first-run onboarding reads. The running daemon already knows a repository after that first question; the configuration keeps it across restarts.

## Settings

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
| Repositories too large to walk | more than 40 000 indexed files: a worktree is checked through git alone (changed and untracked files; the stat cache and, if you enabled it, `core.fsmonitor` and `core.untrackedCache` make that fast) | `config.json` `largeWorktreeFiles` |
| Index reads at once | 2 (the rest wait their turn: ten windows asking together would hold ten reads' memory) | `config.json` `maxParallelQueries` |
| Parse worker: seconds it lives without a file to parse (0 = the daemon parses in its own process, with an 80 MB heap instead of 64) | 300 | `config.json` `{ "parseWorkerIdleSeconds": 300 }` |
| Budgets that make `/status` warn | `p95Ms` 1000, `queueWaitMs` 30000, `rssMb` 250, `busyRate` 0.1 | `config.json` `budgets` `{ "rssMb": 200 }` |
| Weighted-token budgets of a day and of one agent run (events for the desktop app) | none | `config.json` `budgets` `{ "dailyWeighted": 150000000, "runWeighted": 20000000 }` |
| Writing by declaration (`edit`) | `auto`: offered when the gap detector shows the need; no write in `.git`, secrets, conflicted files | `config.json` `write` `{ "mode": "on", "linkedWorktreesOnly": true, "deny": ["**/generated/**"], "gate": { "wholeFileReads": 20, "manualRenames": 3, "windowDays": 30 } }` |
| Trackers to mirror | none | `config.json` `trackers`, see [Trackers](Trackers) |
| Tracker sync while clients are active, idle stop | every 3 min; stops 10 min after the last tool call | `config.json` `trackerSyncMinutes`, `trackerIdleMinutes` |
| Worktree registry, Docker cleanup, ports | see the page | `config.json` `workspaces`: [Workspaces and Docker cleanup](Workspaces-and-Docker-cleanup) |
| Transcript metrics | see the page | `config.json` `metrics`: [Metrics and savings](Metrics-and-savings) |
| Secrets: rotation reminder, import roots | `secrets.rotationDays` 90 | `config.json` `secrets`, `envImport`: [Environment and secrets](Environment-and-secrets) |

## Listening, logs and status

The daemon listens on 127.0.0.1 only and refuses requests with a foreign `Host`, any `Origin`, or
without the `x-codeloupe` header; responses carry `Connection: close`. Calls are logged (tool, latency,
size — no content) to `<home>/calls.jsonl`, the daemon to `<home>/daemon.log`. `/status` adds `latency` (p50/p95 ms,
p95 chars, empty and busy rate of the last 1000 calls, per tool) and `budgets` (`ok` and the `warnings` for what exceeds
`config.json` `budgets`); `/status/history` lists RSS, heap and CPU readings taken while the daemon is used (one a minute,
the last 240).
