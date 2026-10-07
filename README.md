# CodeLoupe

On-demand code index for AI coding agents. Ask for a declaration, a file outline, its usages, callers or type
hierarchy (branch changes soon), and get exactly that piece of code instead of grepping and reading whole files.

- **Any git repository**, no configuration: the base index follows the default branch and is built
  from git objects; every worktree of the repository shares it.
- **No IDE**: the Kotlin compiler's own parser (syntax only, no classpath) and SQLite.
- **One daemon per machine** for every agent window, started on demand; heavy builds run one at a time
  in a short-lived child JVM at low priority, so the daemon stays small (~135 MB).
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
| `hierarchy` | supertypes and subtypes of a type, or what a member overrides and what overrides it |

Usages are resolved without an IDE or compiler: the scopes, imports and aliases a file sees, the receiver's
type where syntax tells it (declared types, `Type(…)`, what a call returns, collection elements in lambdas), and
overloads by argument count. Unsure hits are marked, never dropped. One heuristic: on a receiver of unknown type,
a name the index declares only once (and no library declares, judging by the core API and the files' imports) is
taken as exact. A name that matches several unrelated declarations must be qualified (`Type.member`).

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
| `repo` | repositories and worktrees → base index, child-process builds |
| `query` | read view (with worktree overlays), `find` / `outline` / `symbol` |
| `query.usages` | resolver for references: scopes, receivers, type specs; `usages` / `calls` / `hierarchy` |
| `tools` | the tool catalog shared by MCP, HTTP API and CLI |
| `daemon` | Ktor server, MCP endpoint, job queue, call log |
| `cli` | `codeloupe` commands and the daemon client |

`ParityTest` compares every tool answer with golden output of the Node.js prototype (phase 1); the
TerrioImporter part runs where that repository is checked out (`CODELOUPE_TERRIO`). `UsagesGoldenTest` checks
`usages` on 40 TerrioImporter symbols against a manually verified oracle (`src/test/resources/golden`) and writes
`build/reports/codeloupe/golden-usages.md`: superset of `rg -w`, precision of `exact` (≥ 95 %), candidate share.
