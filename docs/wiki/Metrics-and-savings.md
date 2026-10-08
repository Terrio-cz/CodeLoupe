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
codeloupe metrics boilerplate --since 2026-09-23         # skeleton share of the new code files agents write
codeloupe metrics hooks --since 2026-10-01               # how often the plugin's steering hook spoke and was followed
codeloupe metrics orientation --since 2026-10-01         # ls/find/Glob in the first turns, with and without the session-start context
codeloupe metrics weight --since 2026-10-01              # at which turn the longest sessions would have been warned that they carry too much
```

`collect` writes one JSON report with, per role, median / p75 / sum of cost (relative price units: input 1, 5 min cache
write 1.25, 1 h write 2, read 0.1, output 5), peak context, turns, wall time, code reads and rereads, edit errors, and the
tool categories ranked by what their results cost while they stay in context. The report has the field names of `run/codemetrics.mjs`, the script of the owner's workspace that this command replaces, and on the same transcripts the figures are identical. `gaps` counts a CodeLoupe call
(MCP tool or `codeloupe` on a shell) followed within two turns by a code read, search or `rg`/`cat` naming the same
symbol or file, and calls answered empty, busy or with candidates only. Large windows are read one run at a time; add
`CODELOUPE_OPTS=-Xmx1g` when a single transcript holds huge lines. `config.json` `metrics`: `transcriptDirs`,
`categories` (`[{ "category": "tests", "tool": "regex", "file": "regex", "command": "regex" }]`, tried before the built-in
ones), `defaultCategories` (false = only yours), `prices` (see Money) and `ingestTtlMs` (see below); the hook command is described in [Plugin hooks](Plugin-hooks).

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

The desktop app's Runs, Overview and Gaps screens read the same transcripts through the daemon. The daemon does not watch
them: a UI API call (`/ui-api/v1/runs`, `overview`, `gaps`, `nav`, `events`) starts a pass that reads only the transcripts
that grew since the last one, from the byte offset it stopped at, into `<home>/transcripts.db` (runs, steps, hourly cost,
gaps). Nothing runs between calls, and passes are at least `metrics.ingestTtlMs` (10 s) apart. The first pass over a few
gigabytes of transcripts takes about a minute and goes on in the background (`ingest.running` in the answer). Step texts
are cut to 200 characters and have secrets masked. `config.json` `budgets.dailyWeighted` and `budgets.runWeighted` (weighted
tokens) make the daemon announce, once, the day or the run that goes over, as a `budget.breach` event; new gaps are
`gap.new` events (`/events`, webhooks, the app's notifications).
