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
| State and indexes | `%LOCALAPPDATA%\codeloupe`, `~/Library/Caches/codeloupe`, `$XDG_CACHE_HOME/codeloupe` | `CODELOUPE_HOME`. On Linux and macOS `~` is `$HOME` when that names a directory (an isolated profile, `sudo -E`), as for the hook script, the launcher and git, not the passwd entry the JVM would take; the hook script and the daemon always agree on it. |
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
| Hooks of the plugin | on; steering in `advise` mode, large from 150 lines, at most 40 pieces of advice a session, none after 4 unheeded in a row; session start: worktree state, map off (`map`, `budget` 1200 tokens); session weight: one line at 150k and 300k tokens of context (`weight.warnAt`) | `config.json` `hooks` (see [Plugin hooks](Plugin-hooks)); `CODELOUPE_HOOKS=off` in Claude Code's environment |
| Trackers to mirror | none | `config.json` `trackers`, see [Trackers](Trackers) |
| Tracker sync while clients are active, idle stop | every 3 min; stops 10 min after the last tool call | `config.json` `trackerSyncMinutes`, `trackerIdleMinutes` |
| Worktree registry, Docker cleanup, ports | see the page | `config.json` `workspaces`: [Workspaces and Docker cleanup](Workspaces-and-Docker-cleanup) |
| Transcript metrics | see the page | `config.json` `metrics`: [Metrics and savings](Metrics-and-savings) |
| Secrets: rotation reminder, import roots | `secrets.rotationDays` 90 | `config.json` `secrets`, `envImport`: [Environment and secrets](Environment-and-secrets) |

## Listening, logs and status

The daemon listens on 127.0.0.1 only and refuses requests with a foreign `Host`, any `Origin`, or
without the `x-codeloupe` header, and most routes also want the token ([below](#who-may-call-the-daemon)); responses carry `Connection: close`. Calls are logged (tool, latency,
size — no content) to `<home>/calls.jsonl`, the daemon to `<home>/daemon.log`. `/status` adds `latency` (p50/p95 ms,
p95 chars, empty and busy rate of the last 1000 calls, per tool) and `budgets` (`ok` and the `warnings` for what exceeds
`config.json` `budgets`); `/status/history` lists RSS, heap and CPU readings taken while the daemon is used (one a minute,
the last 240).

## Who may call the daemon

Host, `Origin` and the `x-codeloupe` header keep a web page out; they do not tell one local process from another. Another user
of the machine (RDP, fast user switching, a shared Linux box) could otherwise call `run`, `/jobs` or the release routes as you. So the
daemon makes a **token** on its first start, `<home>/daemon.token` (readable by its owner only, mode set at creation; on Windows the
folder's per-user ACL), and every route that acts for you or shows what you did wants it in the header `x-codeloupe-token`. The file is
that header line, so `curl -H @<home>/daemon.token -H "x-codeloupe: 1" http://127.0.0.1:47391/jobs` presents it without the secret on a
command line. It is kept across restarts. To rotate it, write a new value of 32 to 128 letters, digits, `-` or `_` into the file: the
daemon notices on the next request and no restart is needed (clients read the file again). The token never appears in `/status`, the
logs or the audit.

| Route | Needs the token |
|---|---|
| `GET /status` | no (so that anything can find the daemon); it answers a client's nonce with a proof (below) |
| `GET /env/values` | its own token (`x-codeloupe-env-token`) |
| `/mcp`, `POST /api/<tool>` of a read-only tool, `POST /hook` | only when `api.strict` is on |
| a mutating tool (`run`, `env`, `update`, `edit`, and the MCP `job` tool) over MCP or `/api` | yes: over MCP the call is answered with the way to get it |
| everything else: `/jobs`, `/workspaces`, `/resources`, `/processes`, `/reconcile`, `/ports`, `/events`, `/webhooks`, `/ui-api`, `/shutdown`, `/status/history`, `/session-weight` | yes |

A wrong token is refused wherever it is sent (401), so a client that holds an old one learns it at once. **The default is not
strict**: a read-only code query (`find`, `outline`, `symbol`, ...) that comes with `x-codeloupe` only is still answered, so that the MCP entry
you made before the token existed keeps working. `/status` counts those calls (`auth.withoutToken`); on a machine other people log in to,
update the clients and set `{ "api": { "strict": true } }` in `config.json` (restart the daemon), and the token is required there too.

Clients prove who they talk to before they send it. The CLI, the desktop app and `hook.sh` put a random nonce in the header
`x-codeloupe-nonce` of `GET /status`; the daemon answers in `x-codeloupe-proof` with SHA-256 of `codeloupe-proof:<token>:<nonce>`, which only
a holder of the token can compute. A process that took the port after a crash cannot, and gets neither the token nor a prompt. The CLI also
refuses a daemon whose pid is not the one in `daemon.json`.

What you see after an update:

- **An old daemon still running** has no token file; the new CLI, app and hook send only `x-codeloupe`, as before. Restart it (`codeloupe stop`,
  then `codeloupe start`) to get the token.
- **An old MCP entry** (`--header "x-codeloupe: 1"` only) keeps the read-only tools; `run`, `env`, `edit` and `job` answer *this needs the daemon
  token ...*. Run `codeloupe mcp-config` and add the entry it prints; its `headersHelper` (`codeloupe mcp-headers`) gives Claude Code the token
  on every connection, without writing it into Claude Code's configuration. The plugin does the same with `hooks/mcp-headers.sh`
  once the plugin is updated from the marketplace.
- **An old desktop app** against a new daemon gets 401 on its screens; update the app together with the CLI (the installers do).
- **An old `hook.sh`** against a new daemon keeps working (the hook endpoint is in the read tier) until `api.strict` is on.
