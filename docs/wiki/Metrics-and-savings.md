Where an agent's context and cost go, measured from the transcripts Claude Code already writes. Use it to see what
CodeLoupe saves, and which questions it still cannot answer.

`codeloupe metrics` reads Claude Code transcripts (`~/.claude/projects/<project>/*.jsonl`, subagent runs under
`<session>/subagents/`) and needs no daemon. A run is one session (role `main`), one subagent or one phase-mode run.

## Commands

```bash
codeloupe metrics collect --since 2026-09-23 --until 2026-10-02 --label baseline --dir ~/.claude/projects/<project>
codeloupe metrics compare baseline-2026-10-08.json after-2026-10-20.json   # medians and money per role
codeloupe metrics what-if week.json --roles steward,retro --models claude-haiku-5-5,claude-sonnet-5-5   # same tokens, other models
codeloupe metrics gaps --since 2026-10-01               # where CodeLoupe calls fell short, by week and query shape
codeloupe metrics gaps --since 2026-10-01 --all         # the same, counting every empty answer as a gap (the old rule)
codeloupe metrics boilerplate --since 2026-09-23         # skeleton share of the new code files agents write
codeloupe metrics hooks --since 2026-10-01               # how often the plugin's steering hook spoke and was followed
codeloupe metrics orientation --since 2026-10-01         # ls/find/Glob in the first turns, with and without the session-start context
codeloupe metrics weight --since 2026-10-01              # at which turn the longest sessions would have been warned that they carry too much
codeloupe metrics commands --since 10d --role coder      # which commands agents use most and what their results cost (reads the daemon's transcript database)
```

`collect` writes one JSON report with, per role, median / p75 / sum of cost (relative price units: input 1, 5 min cache
write 1.25, 1 h write 2, read 0.1, output 5), peak context, turns, wall time, code reads and rereads, edit errors, and the
tool categories ranked by what their results cost while they stay in context. The report has the field names of `run/codemetrics.mjs`, the script of the owner's workspace that this command replaces, and on the same transcripts the figures are identical, with one difference by design: CodeLoupe's own tool calls (`mcp__codeloupe__*` and `codeloupe ...` on a shell) are their own category `codeloupe` here, while the script counts them under `other` and `shell_other`, so the category shares of runs that use CodeLoupe differ. Each role also carries a `start` block, the context a run starts with: the median size `S` of the first turn (input + cache read + cache write), what the starting contexts cost as a share of the role's and of the whole period's cost, and the median tokens and share of `S` per source (system prompt, instructions, skills, agent list, deferred tools, MCP instructions, hook context, the first prompt, the rest). `collect` prints it as a `start:` line and `compare` as the change of `S`, of the shares and of the shares per source; the MCP schema sizes (`mcpSchemas`) are not measured here and stay inside `rest`. `gaps` counts a CodeLoupe call
(MCP tool or `codeloupe` on a shell) followed within two turns by a code read, search or `rg`/`cat` naming the same
symbol or file, calls answered busy or with candidates only, and empty answers the agent then looked up elsewhere. Empty answers it accepted (a correct negative for a name the task has yet to create) are counted apart as settled negatives; a later search for a common word (`json`, `description`) or one under 5 characters counts only when it reads a code file the answer named. `--all` counts the old way. Large windows are read one run at a time; add
`CODELOUPE_OPTS=-Xmx1g` when a single transcript holds huge lines. `config.json` `metrics`: `transcriptDirs`,
`categories` (`[{ "category": "tests", "tool": "regex", "file": "regex", "command": "regex" }]`, tried before the built-in
ones), `defaultCategories` (false = only yours), `prices` (see Money) and `ingestTtlMs` (see below); the hook command is described in [Plugin hooks](Plugin-hooks).

## The limits agents reported

`codeloupe metrics limits [--since 30d] [--until] [--account <id>|unknown] [--latest] [--max n] [--format jsonl|csv|json]` exports the `/usage` reports of agent sessions from the transcript database
(5-hour, weekly and weekly-per-model percent, reset time, severity, session, account), so a limit calibration can be re-run without reading the transcripts. See [In the desktop app](Desktop-app#accounts).

## Which commands agents use

`codeloupe metrics commands [--since 10d] [--until] [--role coder] [--run <id>] [--top 15] [--sort cost|calls|tokens] [--json]` ranks the
commands of the tool calls in the transcript database the daemon fills (`<home>/transcripts.db`, see [In the desktop app](#in-the-desktop-app)),
so it needs no transcript scan but shows only what the daemon has ingested; the app's Runs screen has the same table on its Commands tab.
`--since` takes `10d`, `24h`, a day (`2026-10-01`) or an instant (default `7d`); `--role` is the exact role of the run (`terrio-coder`);
`--run` is one run's id (the Runs screen shows it), file name or session.

A call falls in a **group**: a shell command is reduced to its program, subcommand, sorted flags and placeholders for what it passed
(`git diff --stat <path>`, `rg -n <str> <path>`, `gradlew :app:test --console --tests <str>`, `sed -n <n> <path>`), a command with a heredoc is
a group of its own (`heredoc write` for `cat > file <<EOF`, `heredoc python`, `heredoc git commit`), file tools are grouped by tool name, and a
`Write` is `Write (new file)`, `Write (existing file)` (the answer of the call says which; steps stored without it go by the run's history, as Claude Code makes an agent read
a file before it overwrites it) or `Write (rejected)` (it wrote nothing, what it cost is waste). Of a chain such as `cd dir && echo ---; rg x` the first real command counts, the label never
carries an argument, and a line cut at 200 characters that is still setting the scene is `(command cut off)`. Per group the report gives

| Column | Meaning |
|---|---|
| calls, runs | calls of the group, and the runs that made at least one |
| results | tokens the results hold (a quarter token per character, as `collect` counts) |
| arguments | tokens the calls sent (the whole tool input; steps stored before CL-220 only have the shortened description) |
| repeats | calls identical to an earlier call of the same run, and the result tokens they brought again |
| errors | calls that failed |
| cost % | the share of the cost of the runs counted that the results carry: written once to the 1 h cache (2) and read on every later turn (0.1 each) |
| money | that share of what the runs cost at the price table of `collect` (see Money); runs of an unpriced model add nothing and are counted |

and the same by tool category (the categories of `collect`, so the two commands can be compared), by role and by week (ISO week of the start of the
run). The last column of the table lists the three runs that carried most of a group. Checked on the transcripts of 2026-10-01 to 2026-10-11:
for the `terrio-coder` role the calls, result tokens and carried cost of every tool category equal those of `collect` to the unit (docs/plan.md § 8.14).
The daemon stores what the report needs next to each step (the size and a hash of the call's input, the group of a shell call, the answer of a `Write`) and reads
the transcripts again once after an update that adds them.

## Money

Next to the relative units, `collect` prints each role's cost in money, `compare` the money change per role and `what-if` what a
role's runs would have cost on other models. Money is the token counts of each run (input, output, cache reads, 5 min and 1 h
cache writes) times the price of the model that run used; no price is looked up at run time. The prices are a dated table that ships
with CodeLoupe (stamped with the day it was copied from the API reference) and that `config.json` overrides:

```json
{ "metrics": { "prices": { "asOf": "2026-11-01", "currency": "USD", "models": {
  "claude-opus-5-5": { "input": 4, "output": 20, "cacheRead": 0.2, "cacheWrite5m": 5, "cacheWrite1h": 8 },
  "my-model": { "input": 1, "output": 5 } } } } }
```

Prices are per million tokens; only `input` and `output` are required (cache read, 5 min and 1 h writes default to 0.1, 1.25 and 2
times `input`). Models listed replace or add to the built-in ones, a trailing date in a model id (`-20251001`) is ignored. A model
the table does not know is **not guessed**: its runs are counted as unpriced and the model id is listed under the table. `what-if`
keeps each run's token counts, which makes its figure an upper bound on a saving (another model needs other turns, a cheaper one
often more, and may do the task worse) and prints that caveat; use it to pick roles to try on a cheaper model, then compare real
runs with `compare`.

## Savings against a baseline

Savings in the desktop app are measured against a baseline report in the daemon's home. Collect it over a period before CodeLoupe
and store it with `--baseline`; the daemon reads `<home>/baseline.json` again whenever it changes:

```bash
codeloupe metrics collect --since 2026-09-23 --until 2026-10-02 --label baseline --baseline --dir ~/.claude/projects/<project>
```

A finished run whose role the baseline has counts the baseline's mean cost of one run of that role (the mean, because the
runs of a period are compared as totals); every other run counts what it really cost on both sides. The saving is thus a
comparison with the average run of the same role before CodeLoupe, not a controlled benchmark, and the screens say how much
of the cost was compared. The Accounts screen applies the same figure to the runs of each Claude account. Without a
baseline file they show a dash and how to create one.

## In the desktop app

The desktop app's Runs (with its Commands tab), Overview and Gaps screens read the same transcripts through the daemon. The daemon does not watch
them: a UI API call (`/ui-api/v1/runs`, `commands`, `overview`, `gaps`, `nav`, `events`) starts a pass that reads only the transcripts
that grew since the last one, from the byte offset it stopped at, into `<home>/transcripts.db` (runs, steps, hourly cost,
gaps). Nothing runs between calls, and passes are at least `metrics.ingestTtlMs` (10 s) apart. The first pass over a few
gigabytes of transcripts takes about a minute and goes on in the background (`ingest.running` in the answer). Step texts
are cut to 200 characters and have secrets masked. `config.json` `budgets.dailyWeighted` and `budgets.runWeighted` (weighted
tokens) make the daemon announce, once, the day or the run that goes over, as a `budget.breach` event; new gaps are
`gap.new` events (`/events`, webhooks, the app's notifications).
