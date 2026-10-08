# CodeLoupe desktop app

Electron app over the daemon's read-only UI API: token usage and savings, branches and worktrees with their
changed declarations, YouTrack tasks from the mirror, index health, gaps, environment keys and settings.
A tray icon shows the daemon state, RSS and queue; the app starts the daemon when it is down, and it
sends notifications for budget breaches, finished builds, new gaps and daemon outages. It does not monitor
agent runs; the launcher does that. Spec: [../docs/ui-spec.md](../docs/ui-spec.md).

## Requirements

Node.js ≥ 22.12 (npm ≥ 11 honours `allowScripts`: only esbuild's install script runs). Packages come from
the public npm registry only; the Electron binary itself is downloaded by `@electron/get` from Electron's
GitHub releases on first run.

## Run

```bash
npm install
npm run build
npm start          # electron-vite preview of the built app
npm run dev        # dev server with hot reload
npm test           # vitest: request validation, settings, mock API, token contrast, daemon manager
npm run typecheck
npm run lint       # eslint; CI runs npm ci, lint, typecheck, test and build on every OS
```

## Configuration

Settings are saved in `<userData>/settings.json` and edited on the Settings screen.

| Setting | Default | Notes |
|---|---|---|
| Data source | `mock` | `mock` serves the contract from deterministic data, `daemon` calls `/ui-api/v1/*` (CL-39). Daemon status and start/stop are always real. |
| CLI command | `codeloupe` | Used for `start`/`stop`, without a shell. Only a native confirmation dialog can change it, never the page. It must be an `.exe` or `node`/`java` plus a script path; a `.cmd`/`.bat` shim is refused with an explanation. For the Kotlin CLI: `java` with the arguments `-cp <install>/lib/* codeloupe.MainKt` (Java expands the `*` itself, no shell needed). |
| Port | from `<home>/daemon.json`, then `CODELOUPE_PORT`, `config.json`, 47391 | An explicit override is passed to the daemon it starts. |
| Start the daemon when it is down | on | Off after a manual stop (in the app or `codeloupe stop`) until the next manual start. |
| Open at login | off | |

Overrides that apply to one run only: `CODELOUPE_APP_CLI='["java","-cp","C:/…/codeloupe/lib/*","codeloupe.MainKt"]'` and `CODELOUPE_APP_API=mock|daemon`.

Verification modes (development builds only):
- `CODELOUPE_APP_SCREENSHOTS=<dir>` captures every screen in light and dark mode, writes `metrics.json`, then quits.
- `CODELOUPE_APP_TOUR=1|close` visits every screen so memory can be measured from the OS. With `close`, the run ends in the tray.

## Security

- The renderer is sandboxed: `contextIsolation`, no `nodeIntegration`, a strict CSP with `connect-src 'none'`, and the bundle is served from `app://codeloupe`.
- All data goes through preload IPC. Main validates every request (resource, id and query allow-list) and calls only `http://127.0.0.1:<port>`, without an `Origin` header and with `x-codeloupe: 1`.
- Main opens a folder only if it is an existing git worktree from the daemon. It opens a URL only if it is `https` and its origin matches a configured YouTrack instance.
- Permissions, navigation, new windows and webviews are denied.

## Memory

Budget ≤ 300 MB RSS. The GPU and network services run inside the main process, which leaves two processes. Closing the window destroys the renderer and the app stays in the tray. Measured on Windows 11 (sum of working sets):
- about 250 MB right after start;
- about 290 MB after visiting every screen in both themes;
- about 140 MB in the tray only.
