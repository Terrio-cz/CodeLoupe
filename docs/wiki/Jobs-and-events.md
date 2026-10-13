Long commands (tests, builds, deploys) run in the daemon instead of in an agent's turn: the agent starts a job, ends
its turn, and is woken once when it ends — or not at all when the follow-up is deterministic.

## Starting and waiting

```bash
codeloupe job start --slot gradle-test -- ./gradlew test     # prints the id at once
codeloupe job wait J261007-142233-x7k2                       # as a background task: blocks, no time limit
codeloupe job start --wait -- node run/build.mjs              # both in one call
codeloupe job status [id]  ·  codeloupe job cancel <id>
codeloupe job slots                                          # capacity, holders, queue with wait times
codeloupe job slots set gradle-test 2                        # now and in config.json, no restart
codeloupe job move J261007-142240-k3m9 --first               # or --before <id> / --after <id>
```

## How a job behaves

- **Detached**: a job is a child of the daemon, not of the agent session; it outlives the turn and the session. On
  Windows the daemon is started outside the caller's job object (headless `claude -p` runs kill theirs at turn end),
  and jobs run in the daemon's own kill-on-close job object: if the daemon dies they end too, never as orphans.
- Output and errors go straight to `<home>/jobs/<id>.log`; the record (`<home>/jobs.db`) keeps exit code, duration and a
  **compact summary**: test counts (Gradle, Maven, node:test, Jest, pytest), the first failure lines, the last 15 lines.
- **History**: a finished job's record and log stay for at least 7 days (and while it is among the newest 500 jobs; 20,000 is the
  hard cap), then go together. A daemon restart, a forced stop (the write-ahead log is replayed) and an update keep the list; a record
  this version cannot read is skipped, not fatal. At start the daemon logs `jobs: N records kept`, and says so when `jobs/` holds
  more logs than there are records (the records were removed from outside).
- **What the Jobs screen shows**: only jobs the daemon runs (`GET /jobs`, the same list as `job status`). A wrapper that falls
  back to running the command itself when the daemon does not answer (a window opened before `CODELOUPE_PORT` was set, a
  daemon that is down) leaves no record there; such a wrapper should print that it fell back and count the fallbacks.
- **Slots**: `--slot <name>` holds a named resource while running; a busy slot queues the job in the daemon (first come,
  first served). `config.json` `slots` sets capacities (`{ "gradle-test": 2 }`); a slot not listed holds one job.
  Capacities change without a restart: `job slots set <name> <n>` (MCP `job` `action=slots` with `slot` and `capacity`)
  writes `config.json` and applies at once, and an edit of `config.json` is picked up within a second (a file that is not
  valid JSON changes nothing until it is fixed). A raise starts the first waiting jobs at once; a cut never ends a running
  job, it only starts nobody until fewer hold the slot than it allows.
- **Queue order**: `job move <id> --first | --before <id> | --after <id>` (MCP `action=move`) reorders the waiting jobs of
  one slot; a running job never moves, and moving one, an ended or unknown job, or naming a job of another slot is refused.
  Each move is a `job.reordered` event with the slot's new waiting order.
- **Seeing a slot**: `job slots`, the end of `job status`, and `jobs.slots` in `codeloupe status` show per slot the capacity in
  force, what `config.json` sets (`configured`; not listed means 1, a value that is not a positive whole number shows as
  `invalid`), the holders and the waiting jobs with since when and for how long.
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
  which cmd.exe would parse again. The job gets the daemon's environment plus `--env K=V` (values never emitted, and
  stored only sealed while the job is queued or running, see below).
- **Wait once**: `job wait` long-polls the daemon (5 min per request, re-sent, across daemon restarts: a daemon that is gone,
  starting or still ending is asked again every second for up to 2 min) until the job and its follow-ups end, prints the
  compact report and exits with the job's code.
- **Restart**: queued jobs survive a stop or a crash of the daemon. While a job is queued or running, its launch (argv,
  `--env` values, completion steps) is kept in `jobs.db` sealed with AES-256-GCM under a key in `<home>/jobs.key` that the
  OS key store protects (DPAPI, Keychain, libsecret, or `CODELOUPE_PASSPHRASE`), and removed when the job ends. The next
  daemon queues those jobs again in their order (moves included) and judges each by the policy hook again before it starts;
  `job wait` on one of them returns its real result. A running job whose process is still the one it started (same pid and
  start time) is taken over: it keeps its slot, its end is recorded with its exit code (on Windows; elsewhere only the parent
  can read it, so it ends as `lost`) and its completion steps run. A running job whose process is gone, and a queued one whose
  launch could not be stored (no OS key store) or read, is reported `lost` with its log kept. On Windows a job's process
  ends with the daemon that started it (kill-on-close job object), so only queued jobs outlive a daemon there.
  `codeloupe stop` refuses while a job runs or a queued one has no stored launch (`--force` ends them, they become `lost`);
  queued jobs alone do not hold it back.
- **Cancel**: `job cancel <id>` ends the live job of the chain and its whole process tree; after a cancel no job steps run,
  `notify` and `webhook` steps still do.
- **Completion actions** (`--then` after success, `--on-failure` after a failure, in order): a closed set of typed
  steps, never a shell string — `job[@slot]:<command line>` (a follow-up job; the steps after it continue when it ends),
  `notify[:message]` (a `job.notify` event that wakes the agent), `webhook:<url>` (the `job.finished` event to a URL).
  Any step can carry a condition on exit code and summary: `failed==0 && tests>0 ? notify:green` (fields `exit`,
  `tests`, `passed`, `failed`, `skipped`, `seconds`; a count the log did not report never matches).
- **Wake**: the last `job.finished` of a chain carries `wake` — by default true only when something failed if the job
  declared `--then` steps, else always (`--wake always|failure|never`). A launcher subscribed to `job.finished` /
  `job.notify` resumes a headless session only when `wake` is true; `--tag` (default `CODELOUPE_JOB_TAG`) tells it which.
- MCP: one tool, `job` (`action` start / status / cancel / slots / move); waiting is the CLI's job. In the desktop app the Jobs screen shows states, slots and holders, live logs and webhooks ([Desktop app](Desktop-app)).

## Events

`job.started`, `job.finished`, `job.notify`, `job.reordered`, `build.done`, `overlay.refreshed`, numbered (`seq`) and kept in
`<home>/events.db`, scrubbed of secret-looking values (tokens, passwords, `Authorization`, URL credentials) before they are
stored. `GET /events?since=<seq>`; `GET /events/stream` is a server-sent-events stream that resumes after `Last-Event-ID`.

## Webhooks

`codeloupe webhook add <url> [--event job.*]` persists a subscription; each delivery is a JSON POST signed with
`x-codeloupe-signature: sha256=HMAC(<home>/webhook.key, "<x-codeloupe-timestamp>.<body>")`, retried after 2 s, 10 s,
1 min, 5 min and 30 min (also after a daemon restart), logged (`webhook deliveries`). Targets are this machine only, any
port but the daemon's, unless `remoteWebhooks` lists the https origin; redirects are not followed.
