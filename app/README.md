# CodeLoupe desktop app

Electron app over the daemon: token usage and savings with the daemon's latency, memory and CPU against its budgets,
branches and worktrees with their changed declarations and callers, workspaces with the Docker resources, ports and cleanup
they hold, YouTrack tasks from the mirror, jobs with their slots and webhooks, the cost of agent runs from the transcripts,
index health, gaps, environment keys and settings. A tray icon shows the daemon state, RSS and queue; the app starts the
daemon when it is down, and it sends notifications for budget breaches, finished builds, new gaps and daemon outages. It reads;
the only things it changes are a released workspace and a confirmed cleanup, after a native confirmation. It does not follow
what an agent is doing now; the launcher does that. Spec: [../docs/ui-spec.md](../docs/ui-spec.md).

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
npm run dist       # installer for this OS in dist/ (run ./gradlew bundle first); see the root README, Installers
```

## Adding a screen

A screen is one entry in `src/renderer/src/screenList.ts` (route id, title, icon, sidebar group, `g <key>` shortcut) and one
line in `src/renderer/src/views.tsx` (its component; the build fails without it). A sidebar count goes into
`components/Sidebar.tsx`. Data comes from `useApi(resource, id?, query?)`: a resource is one line in `src/shared/request.ts`
(its daemon path and query keys), its type one line in `ResourceMap` of `src/shared/contract.ts`, and its mock answer a case
in `src/main/api/MockApi.ts`. What changes something is not a resource but an action of `src/shared/actions.ts`: the page
asks, main validates, confirms in a native dialog where needed, and does it.

## Configuration

Settings are saved in `<userData>/settings.json` and edited on the Settings screen.

| Setting | Default | Notes |
|---|---|---|
| Data source | `mock` | `mock` serves the contract from deterministic data, `daemon` calls `/ui-api/v1/*` (CL-39). Daemon status and start/stop are always real. |
| CLI command | `codeloupe`; in an installed app the bundled runtime and jar, resolved at every start | Used for `start`/`stop`, without a shell. Only a native confirmation dialog can change it, never the page. It must be an `.exe` or `node`/`java` plus a script path; a `.cmd`/`.bat` shim is refused with an explanation. For the Kotlin CLI: `java` with the arguments `-cp <install>/lib/* codeloupe.MainKt` (Java expands the `*` itself, no shell needed). |
| Port | from `<home>/daemon.json`, then `CODELOUPE_PORT`, `config.json`, 47391 | An explicit override is passed to the daemon it starts. |
| Start the daemon when it is down | on | Off after a manual stop (in the app or `codeloupe stop`) until the next manual start. |
| Open at login | off | |

Overrides that apply to one run only: `CODELOUPE_APP_CLI='["java","-cp","C:/…/codeloupe/lib/*","codeloupe.MainKt"]'` and `CODELOUPE_APP_API=mock|daemon`.

Verification modes (development builds only):
- `CODELOUPE_APP_SCREENSHOTS=<dir>` captures every screen in light and dark mode, writes `metrics.json` (memory, and the milliseconds each screen took to show its data), then quits. `CODELOUPE_APP_SCREENSHOT_ROUTES='[["name","#/hash"],…]'` replaces the list of screens, e.g. with ids from a real daemon.
- `CODELOUPE_APP_CONFIRM=accept` answers the native confirmation dialogs of the Environment screen (delete, replace sources, roll back) so a scripted run can drive them; an installed app ignores it.
- `CODELOUPE_APP_TOUR=1|close` visits every screen so memory can be measured from the OS. With `close`, the run ends in the tray.

## Security

- The renderer is sandboxed: `contextIsolation`, no `nodeIntegration`, a strict CSP with `connect-src 'none'`, and the bundle is served from `app://codeloupe`.
- All data goes through preload IPC. Main validates every request (resource, id and query allow-list) and calls only `http://127.0.0.1:<port>`, without an `Origin` header and with `x-codeloupe: 1`.
- The Environment screen writes the encrypted store only through main: the page sends a value once from a password field (emptied on submit), main hands it to `<cli> env set` on stdin, and no answer, log or argument carries it. Delete, replacing sources and roll back are confirmed in a native dialog. "Kopírovat" exists only where the OS can ask the user to authenticate again (Touch ID on macOS); elsewhere a value cannot be copied out at all.
- Workspaces actions (release a worktree, confirm a cleanup) change the real daemon only after main has checked the request against the daemon's own registry and plan and the user said yes in a native dialog that lists what goes; the page never sends a path to delete, only plan keys.
- Job logs: the page names a job id; main reads only `<home>/jobs/<id>.log` of a finished job, strips terminal codes and masks credential-looking values. The live event stream is opened while the Jobs screen is open and passes on only the event type and job id.
- Settings → Claude Code runs the `claude` CLI (no shell, fixed argv: `mcp add|remove`, `plugin marketplace add`, `plugin install`) only after a native confirmation that lists the commands; the page cannot click it, and the app never writes Claude Code's files itself. See the root README, section Claude Code.
- Main opens a folder only if it is an existing git worktree from the daemon. It opens a URL only if it is `https` and its origin matches a configured YouTrack instance.
- Permissions, navigation, new windows and webviews are denied.

## Memory

Budget ≤ 300 MB RSS. The GPU and network services run inside the main process, which leaves two processes. Closing the window destroys the renderer and the app stays in the tray. Measured on Windows 11 (sum of working sets):
- about 250 MB right after start;
- about 290 MB after visiting every screen in both themes;
- about 140 MB in the tray only.

Animations repaint in software (no GPU), and Chromium holds the raster memory for a few seconds. Switching quickly
through every screen peaks about 35 MB above the pre-revamp app and is back at its level after 5 s idle; the motion
rules that keep it there (no staggered entrances, no transform/opacity animation inside a scroller) and the
measurements are in [docs/design-revamp.md](../docs/design-revamp.md), Paměť.

## Window

The window has no system title bar: the page draws its own (name and version, a drag region: move, double-click to
maximise, Windows 11 snap layouts) and the OS keeps only its caption buttons as an overlay in the design's colours
(`src/main/windowChrome.ts`, recoloured when the theme changes). Screenshot mode captures the page only, without the
caption buttons.
