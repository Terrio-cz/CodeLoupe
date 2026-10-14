`app/` holds the Electron desktop app (tray, notifications, daemon start/stop). Screens, light and dark: Overview (cost and
savings, p95 latency, daemon memory and CPU with the budget warnings), Branches (changed declarations, callers, tests, the
runs of the task), Workspaces (every registry state with its Docker resources, ports, disk and memory; release and confirmed
cleanup), Tasks, Jobs (states, slots with capacity, holders and the queue with wait times, live, logs with the summary first, chains, webhooks; set a slot's capacity and reorder its waiting jobs), Runs (what each agent
run cost and where, and a Commands tab: which commands the agents use most and what they cost), Index, Gaps (the weekly gap report), Environment and Settings. They read the daemon's UI API and a few
of its read-only routes; the two actions that remove something ask in a native dialog first, and the two undoable ones on the Jobs screen (a slot's capacity, the order of a slot's queue) do not. See [app/README.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/app/README.md)
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

A Claude account may also carry `tokenSecret`, the name of the secret that holds its long-lived token (`claude setup-token`) for unattended runs; the app keeps the name
when it saves the file, CodeLoupe itself never reads the secret. `GET /ui-api/v1/accounts` returns the name, `tokenConfigured` (the secret exists in the store), the cost
of the last 5 hours (`weighted5h`, the session window) next to the 7 days, and `limit`: `{ state, kind, resetsAt, observedAt, source }`. The limit comes from the usage-limit
answers Claude Code writes into the transcripts ("You've hit your weekly limit · resets Oct 11, 8pm (Europe/Prague)"): the reset time is the line's own `quotaLimits.resetsAt`,
else read from the text in the zone it names. `state` is `limited` while the reset lies ahead, `ok` once it passed, `unknown` when the account's transcripts hold no such answer;
`source` is `transcript`. The Accounts screen shows the limit, the reset time and the secret name.

**Limit snapshots (CL-226).** Every `/usage` report an agent session ran is kept in `transcripts.db`: per meter (5-hour `session`, `weekly_all`, `weekly_scoped` with its model) the time, percent, reset time and severity, with
the session id and the account. They are kept for good (also when Claude Code deletes the transcript or the runs are read again), and a session that only ran `/usage` is stored too.
`GET /ui-api/v1/limits?since=7d&account=<id|unknown>&limit=5000` returns `latest` (the newest snapshot of every meter per account) and the `snapshots` series; `codeloupe metrics limits [--since] [--until] [--account] [--latest] [--format jsonl|csv|json]` exports them. Metadata only, no conversation text.
The **account** of a session is the account whose `projects` folder holds its transcript, and `null` (undetermined) when no listed account does or two share the directory; it is never guessed. Two logins sharing one config directory (`~/.claude`)
are not told apart by the location, so the plugin's `SessionStart` hook stamps every session with its own login: the account uuid Claude Desktop gives the session, else the account a launcher names in `CODELOUPE_ACCOUNT`,
else the `oauthAccount` of the `.claude.json` of a config directory no two accounts share (see [Plugin hooks](Plugin-hooks#account-stamp)), and keeps only `identity`, a 12-character fingerprint of the account uuid, in `<home>/session-identities.jsonl` (`session id -> fingerprint`; never the uuid or the e-mail).
The ingest gives the stamp to the session's transcripts and to its subagents' (the `SubagentStart` hook stamps a session that began before the daemon). A transcript that names its own account (`bridge-session` lines) counts first.
With an identity the account is: the account that names it in `accounts.json` (`"identity": "<fingerprint>"` on a `claude` entry, bare or as `identity-<fingerprint>`), else the location when that is an account with a directory of its own, else the anonymous
account `identity-<fingerprint>` (also for the implicit default account and for any account on `~/.claude`, which several logins share). Run the export, see which `identity-…` accounts report, and name the fingerprint of A and B in `accounts.json` to give them
their ids. `--account unknown` then holds only the sessions with neither a location nor an identity. A stamp that comes after the transcript was read (or an `accounts.json` that changes) moves the stored rows to the new account.
Sessions started before the stamp stay as they were (`default`, no identity), and a login switched inside a running session keeps the identity it started with. `hooks.stampAccount: false` turns the stamp off.

**Desktop instance (CL-231).** Two Claude Desktop instances (two user-data directories, e.g. the main profile and one started with `--user-data-dir`) can write their Claude Code sessions into the same `~/.claude`. Give each account the user-data directory of its instance as `"desktopDir"` on its `claude` entry, as the real path (a packaged app redirects `%APPDATA%`, e.g. `C:\Users\you\AppData\Local\Packages\Claude_<id>\LocalCache\Roaming\Claude-ucet2`). The instance keeps one `claude-code-sessions/**/local_*.json` per session, and CodeLoupe reads from each only the `cliSessionId` (the start of the file, nothing else: no title, prompt or token is read, kept or logged); a session an instance lists belongs to its account, before the identity, the `projects` folder or anything else. A session no instance lists falls back as before; a session two instances list goes to the newer file. New files and a changed `accounts.json` move the stored rows at the next pass (a row is not taken back when a directory stops being listed). `"email"` on a `claude` entry names the e-mail of that login and is what the Accounts screen shows for it, ahead of the e-mail in the profile of the config directory (the same for every account sharing one). Once any account has a `desktopDir`, `GET /ui-api/v1/accounts` (cost 7 days and 5 hours, savings, last use, working directories), `overview?account=` and the limits export count what is stored for the account's sessions instead of what lies under its `projects` folder, so accounts sharing a config directory show their own numbers.
