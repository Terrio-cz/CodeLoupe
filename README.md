# CodeLoupe

On-demand code index for AI coding agents. Ask for a declaration, a file outline or (soon) usages and
branch changes, and get exactly that piece of code instead of grepping and reading whole files.

- **Any git repository**, no configuration: the base index follows the default branch and is built
  from git objects; every worktree of the repository shares it.
- **No IDE**: tree-sitter (WASM) parsing and SQLite (`node:sqlite`), no native builds.
- **One daemon per machine** for every agent window, started on demand; heavy builds run one at a time
  in a short-lived child process.
- **MCP** over Streamable HTTP (stateless) plus the same tools on a CLI.

Languages: Kotlin (Java next).

> **Status:** this Node.js implementation is the phase-1 prototype. CodeLoupe is being ported to Kotlin/JVM
> (YouTrack CL-56); the prototype is removed once the port reaches parity. Status and roadmap: [docs/plan.md](docs/plan.md) (Czech).

## Requirements

Node.js ≥ 22.13, git ≥ 2.31.

## Use

```bash
npm install
node bin/codeloupe.mjs outline OrderService          # from inside a repository
node bin/codeloupe.mjs symbol "OrderService.handle(_)"
node bin/codeloupe.mjs find "*Repository" --kind interface
node bin/codeloupe.mjs status
```

The first query in a repository builds its index (seconds); later queries take milliseconds. The
daemon starts on the first CLI call; `codeloupe start` / `stop` manage it explicitly.

### Claude Code

`node bin/codeloupe.mjs mcp-config` prints the `.mcp.json` entry:

```json
{ "codeloupe": { "type": "http", "url": "http://127.0.0.1:47391/mcp", "headers": { "x-codeloupe": "1" } } }
```

Tools take `root` — the absolute path of the repository or worktree to answer for.

| Tool | Returns |
|---|---|
| `find` | declarations by name, `Type.member` or glob: `path:lines [container] signature` |
| `outline` | members of a file or type with line ranges, no bodies |
| `symbol` | one declaration's source (KDoc, annotations, body) by `Type.member`, `member(ParamType)`, `pkg.Type` or `File.kt:line`; large types collapse to header + members |

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

The daemon listens on 127.0.0.1 only and refuses requests with a foreign `Host`, any `Origin`, or
without the `x-codeloupe` header. Calls are logged (tool, latency, size — no content) to
`<home>/calls.jsonl`, the daemon to `<home>/daemon.log`.

## Develop

```bash
npm test
```
