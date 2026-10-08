Long commands (tests, builds, deploys) run in the daemon instead of in an agent's turn: the agent starts a job, ends
its turn, and is woken once when it ends — or not at all when the follow-up is deterministic.

## Starting and waiting

```bash
codeloupe job start --slot gradle-test -- ./gradlew test     # prints the id at once
codeloupe job wait J261007-142233-x7k2                       # as a background task: blocks, no time limit
codeloupe job start --wait -- node run/build.mjs              # both in one call
codeloupe job status [id]  ·  codeloupe job cancel <id>
```

## How a job behaves

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
- MCP: one tool, `job` (`action` start / status / cancel); waiting is the CLI's job. In the desktop app the Jobs screen shows states, slots and holders, live logs and webhooks ([Desktop app](Desktop-app)).

## Events

`job.started`, `job.finished`, `job.notify`, `build.done`, `overlay.refreshed`, numbered (`seq`) and kept in
`<home>/events.db`, scrubbed of secret-looking values (tokens, passwords, `Authorization`, URL credentials) before they are
stored. `GET /events?since=<seq>`; `GET /events/stream` is a server-sent-events stream that resumes after `Last-Event-ID`.

## Webhooks

`codeloupe webhook add <url> [--event job.*]` persists a subscription; each delivery is a JSON POST signed with
`x-codeloupe-signature: sha256=HMAC(<home>/webhook.key, "<x-codeloupe-timestamp>.<body>")`, retried after 2 s, 10 s,
1 min, 5 min and 30 min (also after a daemon restart), logged (`webhook deliveries`). Targets are this machine only, any
port but the daemon's, unless `remoteWebhooks` lists the https origin; redirects are not followed.
