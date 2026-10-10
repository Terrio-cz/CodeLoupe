What CodeLoupe's answers cost an agent compared with grep and whole-file reads, and what it costs to have the tool, measured
by a script anyone can run ([Development](Development#measuring)). Claims here are measurements of these cases, not
guarantees.

## What an answer costs

An agent working on a Kotlin repository keeps asking a few questions: where is this declared, what does this file
contain, who uses this, who calls this, what are its subtypes, what did my branch change. Without an index it answers
them with `rg` and by reading files. CodeLoupe answers each with one call that returns the relevant piece of code.
What the agent has to read, median over the questions of each kind on two public repositories (tokens are characters
divided by 3.16; method and every row in [docs/benchmarks.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/benchmarks.md)):

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Terrio-cz/CodeLoupe/main/docs/benchmarks-share-dark.svg">
  <img alt="CodeLoupe answer as a share of grep + read" src="https://raw.githubusercontent.com/Terrio-cz/CodeLoupe/main/docs/benchmarks-share.svg" width="830">
</picture>

| Task | Tool | CodeLoupe | grep + read | grep, minimal |
|---|---|---:|---:|---:|
| Read a type | `symbol` | 326 | 789 | 322 |
| Read a member | `symbol` | 117 | 1,746 | 118 |
| Outline of a file | `outline` | 206 | 615 | 197 |
| Who uses a type | `usages` | 560 | 2,997 | 626 |
| Who calls a member | `calls` | 169 | 1,352 | 286 |
| Subtypes of a type | `hierarchy` | 59 | 464 | 102 |
| Text search, 30 hits | `grep` | 1,298 | n/a | 1,497 |
| What a branch changed | `changes` | 2,423 | 79,728 | 2,247 |

*grep + read* is what an agent without an index typically does: `rg` with context lines, a whole-file read, the full
`git diff`. *grep, minimal* is a best case that assumes the agent never reads a line it does not need (for source
lookups it is given the exact line range, for the branch `git diff --stat`). Against grep + read, CodeLoupe's answers
are 3–41 % of the size (8 % summed over all questions) and take one call where grep needs two for source lookups.
Against the best case they are about the same for source lookups, outlines and branch changes (8 % more), and smaller
for usages, callers and subtypes. The default answers are compact: `hierarchy` lists the direct subtypes and `changes` the
changed declarations; supertypes (`supers=true`, `deep=true` for transitive links) and the callers and tests of each
changed declaration (`callers=true`) are one more parameter away.

Beyond navigation, and not part of the benchmark: worktrees of one repository share one index and each adds only its own
edits; long commands run in the daemon so an agent's turn can end ([Jobs and events](Jobs-and-events)); a tracker mirror
answers issue reads locally; `codeloupe metrics` shows from Claude Code transcripts where an agent's context goes
([Measuring agent runs](Metrics-and-savings)).

## Method and results against GitNexus

`node tools/benchmark.mjs` asks the same questions of a grep-and-read baseline, CodeLoupe and
[GitNexus](https://github.com/abhigyanpatwari/GitNexus) over public repositories at pinned commits
(`JetBrains/Exposed` `023a6a3`, 319 Kotlin files without tests; CodeLoupe `f370022`, 457 files), and writes
[docs/benchmarks.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/benchmarks.md), [docs/benchmarks.json](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/benchmarks.json) and the chart below. Run of
2026-10-09 on Windows 11, i7-13700F, 64 GB, CodeLoupe 0.1.0 (commit `38eab82`), `gitnexus@1.6.12` from npm.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Terrio-cz/CodeLoupe/main/docs/benchmarks-dark.svg">
  <img alt="Median tokens read per question" src="https://raw.githubusercontent.com/Terrio-cz/CodeLoupe/main/docs/benchmarks.svg" width="830">
</picture>

Tokens read per question, median over 15–16 questions of each kind (2 for the branch, 6 for text search):

| Task | CodeLoupe | GitNexus | grep + read | grep, minimal |
|---|---:|---:|---:|---:|
| Read a type | 326 | 1,962 | 789 | 322 |
| Read a member | 117 | 848 | 1,746 | 118 |
| Outline of a file | 206 | n/a | 615 | 197 |
| Who uses a type | 560 | 1,542 | 2,997 | 626 |
| Who calls a member | 169 | 666 | 1,352 | 286 |
| Subtypes of a type | 59 | 1,352 | 464 | 102 |
| Text search, 30 hits | 1,298 | n/a | n/a | 1,497 |
| What a branch changed | 2,423 | 21,740 | 79,728 | 2,247 |

GitNexus 1.6.12 has no outline or text-search tool, so those rows are `n/a`. Its `context` answers are JSON cards (callers,
callees, process membership), not the same content as CodeLoupe's, so the comparison is of what is read, not of what is
learned. Both tools answered every question they have a tool for; for 8 of GitNexus's 82 answers (names that exist more
than once) it needed a second call with the file path, and both answers are counted.

### Cost of having the tool

Same run:

| | CodeLoupe | GitNexus |
|---|---:|---:|
| Tool definitions in the agent's context (`tools/list`) | 14 tools, 4,223 tokens | 17 tools, 22,140 tokens |
| First index, Exposed / CodeLoupe | 7.3 s / 2.7 s | 128 s / 68 s |
| Peak memory while indexing, Exposed | 517 MB | 2,583 MB |
| Memory after the queries | 206 MB daemon, both repositories | 3,325 MB MCP server after 82 queries (114 MB at start) |
| CPU while idle, 30 s | 0 ms | 15 ms |
| Warm call (median, per question kind) | 10–11 ms; 26 ms text search; 750 ms branch changes | 155–213 ms; 214 ms branch changes |
| Index on disk, Exposed / CodeLoupe | 62 MB for both | 1,067 MB / 141 MB |
| Files written into your checkout by indexing | 0 | 8 (`AGENTS.md`, `CLAUDE.md`, `.claude/skills/`; off with `--skip-agents-md` and `--skip-skills`) |

### What the numbers do not show

- They count what an agent reads, not whether it finishes a task; the controlled runs of real agents are in
  [Agent runs](#agent-runs) below, and they are less flattering.
- One run on a shared workstation: token counts are deterministic, timings and memory are indicative and vary between
  runs.
- Two repositories, one of them CodeLoupe's own, questions chosen by a fixed rule that favours grep (names unique in the
  repository). GitNexus's default database buffer pool (428 MiB here) was too small to index Exposed; the benchmark sets
  2 GiB for `analyze`, as the error message suggests. GitNexus's worktree handling and newer builds than 1.6.12 were not
  measured. An IDE-based MCP server cannot be started by a script and is not measured.

## Agent runs

The tables above count what an answer costs. They do not say what a whole agent run costs, or whether an agent that has
the tool uses it. These are controlled experiments on real agents, kept in
[docs/plan.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/plan.md) § 8.4 and
[docs/context-audit.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/context-audit.md). The honest summary:
**the tool's own costs came down, but the agents barely call it, so most of the agent-cost saving measured so far comes from
shorter prompts and fewer tool definitions, not from CodeLoupe's answers.**

### Method

- One fixed task, two variants (A: before the change, B: after), run with `claude -p --agent <role>` on a scratch copy of a
  real multi-agent workspace of a Kotlin backend (planner, reviewer, coder, tester, task-suggester roles). The live
  workspace is never touched. Same model and effort, same prompt, same repository clones (closed tasks, at the commit just
  before they landed), each variant with its own CodeLoupe daemon on a separate home and port. A and B run at the same time
  where the machine allows, at most two runs at once.
- Only counts from the transcripts Claude Code writes, never their content. Cost is the CLI's USD; *weighted units* are
  input + 1.25 x 5-minute cache write + 2 x 1-hour cache write + 0.1 x cache read + 5 x output tokens; a turn is one model
  call. Tool JSON costs about 5 characters per token, source text about 3.16.
- 3-5 replicates per cell. **Replicates of the same variant differ by 15-40 %**, as much as most A-versus-B differences
  below, so a difference smaller than that is not reported as an effect; the tables give ranges where it matters.

### Planner and reviewer with CodeLoupe (CL-23)

24 runs (Opus, high effort), 5 closed tasks, one run per task and role (the reviewer twice on two of them). Sums over the
five tasks. A: reviewer before the CodeLoupe change, `rg` + read + git; planner before the task index. B: today's agents
with the CodeLoupe tools.

| Role | | Cost USD | Units (thousand) | Model calls | Peak context (thousand tokens, max) | Wall (s) | Code reads | CodeLoupe calls |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| reviewer | A | 5.83 | 1,628 | 58 | 102 | 1,037 | 55 | 0 |
| reviewer | B | 4.89 | 1,352 | 48 | 94 | 893 | 40 | 1 |
| planner | A | 6.56 | 1,814 | 76 | 92 | 1,632 | 96 | 0 |
| planner | B | 6.38 | 1,828 | 102 | 87 | 1,310 | 101 | 10 |

The reviewer was 16 % cheaper and the planner unchanged (-3 % cost, +1 % units, +34 % model calls, -20 % wall time). **None of
that can be credited to CodeLoupe**: in B the agents called it once in seven reviewer runs and ten times in five planner runs
(`find`, `outline`), and used `rg`, `git` and file reads for everything else. What differs between A and B is mostly the
agent instructions, which were rewritten in the same period.

**A shorter reviewer lost a check.** The first-round findings of the real reviews are known for two tasks (6 and 4).
The reviewer with the shortened body (8.6 thousand characters instead of 13.9 thousand) found all 6 on one task and 3 of 4
on the other, in both replicates: it had moved one delivery check to a reference file it never opened. Putting the check
back into the body, twice, and re-running two replicates per version (same packet and clones):

| Reviewer body | Findings, task 1 (of 4) | Findings, task 2 (of 6) | Cost USD (task 1 / task 2) | Model calls |
|---|---|---|---|---|
| before the shortening (A) | 4, 4 | 6, 6 | 1.20 / 1.18 | 11 / 13 |
| shortened (B) | 3, 3 | 6, 6 | 0.86 / 1.34 | 7 / 15 |
| fix 1: a sentence saying the pair must be checked | 3, 3 | 6, 6 | 1.11-1.31 / 1.07-1.43 | 13-19 / 13-21 |
| fix 2: a missing pair is a finding, with a proposal | 4, 4 | 6, 6 | 1.39 and 0.98 / 1.59 and 1.27 | 26 and 12 / 25 and 17 |

The first fix was not enough: the reviewer mentioned the pair but listed it as "unknown, verify before landing". Only an
instruction that makes the omission itself a finding brought the finding back, at a cost about a fifth higher than the
shortened body (mean 1.31 against 1.07 USD; A 1.10). The lesson for anyone trimming an agent prompt: re-run the
fixed-findings check, because a cheaper run that finds less is not cheaper.

### Dispatcher, planner and issue reads (CL-92, CL-95, CL-30)

28 runs (Opus, high), 47.65 USD in total. A: the task-suggester and planner before the local task index (YouTrack API
tools, no CodeLoupe tools). B: today's agents reading issues and task queries from CodeLoupe's local tracker mirror. The
suggester writes only its plan, and every plan passed the workspace's own plan check (16 of 16).

| Run | n (A / B) | Units, thousand: median (range), A | same, B | Mean USD A / B | Model calls, median A / B |
|---|---|---|---|---|---|
| suggester, automatic choice | 5 / 5 | 718 (362-1,138) | 602 (387-702) | 2.49 / 1.90 | 34 / 30 |
| suggester, one epic | 3 / 3 | 448 (425-546) | 492 (481-712) | 1.60 / 1.84 | 27 / 32 |
| planner, task 1 | 3 / 3 | 364 (320-385) | 381 (367-405) | 1.28 / 1.38 | 16 / 19 |
| planner, task 2 | 3 / 3 | 349 (337-374) | 332 (330-388) | 1.27 / 1.20 | 15 / 24 |

What changed as intended: tracker API calls per suggester run 39 to 0.2 (automatic) and 29 to 0.3 (epic); one issue read is
about half the size (2.3-2.8 thousand characters against 4.4 thousand). What did not change: **cost**. Pooled over 8 runs per
variant the suggester's median went from 575 to 551 thousand units (-4 %, mean -14 %; Mann-Whitney p = 0.31 / 0.40 / 0.80 for
automatic, epic, pooled), and the spread between replicates of one variant is larger than the difference. The planner is
unchanged (median 357 to 374 thousand, p = 0.48).

### Starting context of an agent (CL-75, CL-76, CL-177)

First-turn context, medians of 3-5 replicates per role and variant on the same fixed prompt.

| Change | Measured | Before | After |
|---|---|---:|---:|
| Roles keep only the MCP tools they use (coder / reviewer / tester / planner) | first turn, thousand tokens | 18.4 / 25.0 / 17.3 / 26.4 | 11.0 / 10.6 / 10.0 / 12.1 |
| Project instructions file not loaded into subagents | first turn, thousand tokens, all four roles | 20.3 / 19.9 / 19.3 / 21.4 | 11.0 / 10.6 / 10.0 / 12.1 |
| Session-start payload of the main session (27 sessions before, 5 after) | first turn, thousand tokens | 74.6 | 64.5 |

The roles still carry CodeLoupe's tool definitions: 1.5 (coder), 1.4 (tester), 2.2 (reviewer) and 2.5 (planner) thousand
tokens at the start of every run, 0.65 % of total agent cost in the period measured. Moving the project rules out of the
shared instructions file into the agent bodies did not cost any rule: 54 of 54 rule questions answered correctly in both
variants, no policy slip. Removing the tools that were dropped from the roles (two code-graph servers, the database client and three tracker
reads) was safe too: none of the 16 runs that had them back called one.

### Waiting for long commands (CL-88)

Turns whose only call is waiting (`sleep`, a status poll, `job wait`) were 9.1 % of all agent cost in 2,651 live runs.
The controlled task: a coder agent compiles three clean clones one after another, read-only. A: before jobs (long commands in the
background, polled with `sleep` every few minutes), B: `job start` then `job wait` through the daemon. Two command lengths, 3 replicates each, 12 runs, sums over the replicates.

| Command length | | Waiting turns (per run) | Waiting units of total | Cost per run USD | Wall (s) |
|---|---|---:|---:|---:|---:|
| about 3 min | A | 8 (2.7) | 28.2 of 87.7 thousand = 32.2 % | 0.058 | 549 |
| about 3 min | B | 12 (4.0) | 28.3 of 137.3 thousand = 20.6 % | 0.092 | 552 |
| 4-5 min | A | 14 (4.7) | 47.3 of 118.6 thousand = 39.9 % | 0.079 | 816 |
| 4-5 min | B | 12 (4.0) | 27.5 of 101.6 thousand = 27.1 % | 0.068 | 810 |

Waiting units per run, B against A: 1.00 at 3 minutes and 0.58 at 4-5 minutes. Applied to the live 9.1 % that is about 5.3 %,
not the target of 1 %. The runs showed why: a headless `claude -p` waits in the foreground, so every long command costs one
waiting turn however it is waited for, and in five of six B runs the agent wrote its first `job start` with a doubled
command prefix and paid for a second start and a second wait.

### Dropping two tool servers (CL-47)

18 runs (reviewer on two tasks, planner on one; 3 replicates): A has the GitNexus and IDE MCP servers enabled, B has them removed.

| Role, task | A / B cost USD (3 runs) | Units, thousand | Calls to the two servers | Known findings found |
|---|---|---|---|---|
| reviewer, task 1 | 1.20 / 1.18 | 334 / 333 | 0 / 0 | 4 of 4 in all 6 |
| reviewer, task 2 | 1.32 / 1.12 | 398 / 314 | 0 / 0 | 6 of 6 in all 6 |
| planner, task 2 | 1.42 / 1.33 | 420 / 378 | 0 / 0 | n/a |

A cost 7-10 % more (spread inside a cell 0.84-1.55 USD), which matches the first call of a run carrying 21.0 instead of 11.1
thousand tokens of tool definitions. In live use over the same period the two servers were called 0 times. The IDE server
could not be reached in the run (the IDE was closed), so its schema cost was not measured; both servers were removed.

### Session-start map (CL-148)

Does a repository map in the session-start context help? 40 short read-only sessions (Sonnet; 10 questions about the code, each
twice per variant), the full plugin, two daemons differing only in the map.

| | no map | map |
|---|---|---|
| context added at start | about 60 tokens | about 1,220 tokens |
| orientation calls (`ls`, `find`, `Glob`) per session | 0.25 | 0.15 |
| file reads / searches per session | 0.9 / 2.0 | 0.9 / 2.0 |
| turns per session | 3.4 | 3.3 |
| weighted cost per session, mean / median | 44,451 / 39,172 | 47,366 / 43,370 (+6.6 % / +10.7 %) |

The map was dearer in 7 of 10 questions and cheaper in 3. It stays off. In none of the 40 sessions did the model call a
CodeLoupe tool.

### Changes to the tool itself, measured the same way

Old and new build alternated on the same machine, medians.

| Change | Measured | Before | After |
|---|---|---:|---:|
| AOT cache for the CLI and the daemon (CL-150) | CLI `find`, ms (local, 10-12 calls) | 302-322 without archive, 182-213 dynamic archive | 135-142 |
| | same, CI medians Linux / Windows / macOS arm64 / macOS Intel, vs the dynamic archive | 136 / 346 / 190 / 469 | 112 / 276 / 144 / 373 (-18 to -24 %) |
| | daemon start to listening, ms | 1,031 | 559 |
| Parallel workspace scan (CL-144), 76 workspaces | four routes at once, s, three series | 2.52 / 1.30 / 1.23 | 0.68 / 1.09 / 0.64 |
| Overlay refresh after an edit (CL-149) | edit with 8 other overlays, ms, three series | 160 / 146 / 171 | 67 / 73 / 75 |

### What did not work, and what the numbers do not show

- **Agents barely call CodeLoupe (CL-180).** 1 call in 7 reviewer runs, 10 in 5 planner runs, 0 in 40 sessions of the map
  experiment, and the roles' grep habit survives a tool that is connected. Until that changes, runs with and without the tool
  differ in what the prompts say, not in what the tool answers. Why (the packet already holds the diff, the `rg` habit,
  tool descriptions, prompt wording) is still open (CL-180).
- **The suggester's cost did not fall (CL-182).** The local task index removed the tracker API calls, but the agent still reads
  as many issues as before (16.6 and 18.3 `issue` calls per run against 14.0 and 16.7 API reads), because its instructions
  tell it to read each candidate. Tracker reads are still 10.1 % (automatic) and 13.9 % (epic) of a run's cost.
- **A trimmed prompt can lose a check** (reviewer, above), and a session-start map cost more than it saved.
- **Waiting is still a model call.** The per-command floor is one call, not zero.
- Small samples: 3-5 replicates, a single workspace and a single set of tasks, two runs at a time on a busy machine (wall time
  is indicative), and the replicate-to-replicate variance of 15-40 % noted above. Findings are counted against the known
  findings of the real reviews, not graded independently, and the agents' answers are not scored for quality beyond that.
