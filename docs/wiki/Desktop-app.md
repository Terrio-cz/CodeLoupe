`app/` holds the Electron desktop app (tray, notifications, daemon start/stop). Screens, light and dark: Overview (cost and
savings, p95 latency, daemon memory and CPU with the budget warnings), Branches (changed declarations, callers, tests, the
runs of the task), Workspaces (every registry state with its Docker resources, ports, disk and memory; release and confirmed
cleanup), Tasks, Jobs (states, slots and holders, live, logs with the summary first, chains, webhooks), Runs (what each agent
run cost and where), Index, Gaps (the weekly gap report), Environment and Settings. They read the daemon's UI API and a few
of its read-only routes; the two actions that change something ask in a native dialog first. See [app/README.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/app/README.md)
(running it, settings, security model, memory) and the UI spec
[docs/ui-spec.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/ui-spec.md) (Czech).

The installed app is the easiest way to get everything: [Installers and updates](Installers-and-updates). A tray icon
shows the daemon's state, RSS and queue; the app starts the daemon when it is down (not after `codeloupe stop`, which
writes `<home>/stopped` until `codeloupe start` removes it) and sends notifications for budget breaches, finished builds,
new gaps and daemon outages. Its memory budget is 300 MB RSS.

## Accounts

`<home>/accounts.json` lists the Claude Code accounts of this machine (each a config directory, `CLAUDE_CONFIG_DIR`) and the YouTrack instances to mirror; the
desktop app writes it, the daemon reads it afresh on every call. Without it the one account is `~/.claude`. The transcripts of every listed account are ingested
(`<configDir>/projects/*`) and attributed to it by the folder they lie in, so `GET /ui-api/v1/accounts` shows each account's cost for 7 days, last use and working directories
that called CodeLoupe in the last 15 minutes, and `GET /ui-api/v1/overview?account=<id>` narrows the Overview to one. A YouTrack account is `{ id, label, url, projects, token }` where
`token` is the name of a global secret in the store (`YOUTRACK_TOKEN_<ID>`); the daemon mirrors it like a tracker of `config.json` (a tracker of that file wins a name clash), reading the
token from the store at most every 30 seconds. The API never returns a token, only whether one is stored.
