*Last updated: 2026-10-10.*

This page holds the evidence behind [Agent runs](Agent-runs): how the numbers were made, then every experiment with its table and its limits. The short page has the takeaways, the bottom line and one overview table of all changes.

## How to read the numbers

**An agent run** is one call of one agent, from its first prompt to its final report, on a fixed task with a fixed prompt. Every number comes from the log files Claude Code writes for each run; only counts are read, never the content. A **turn** is one model call. A **helper agent** is an agent started by another agent.

**Roles.** A task goes through the *flow*: plan, code, review and test, then land.

| Role | What it does |
|---|---|
| Planner | Reads a task and the code and writes the plan, with the risks it sees, before any code is written. |
| Coder | Implements the plan. |
| Reviewer | Reads the finished change and reports findings against the task and the repository's rules. |
| Tester | Builds the change, runs the tests and exercises the service. |
| Dispatcher | Picks which open tasks to start next, so that no two of them edit the same code. |

**Tasks.** The reviewer and planner experiments replay closed changes of the author's backend service, at the commit just before each one landed. They are called task A to E here and differ in size and kind. For the reviewer, the real review of the task gives a list of known findings, so a run can be checked for what it missed.

| Task | The change | Known review findings |
|---|---|---|
| A | security headers and static assets of the API documentation (16 files) | 4 |
| B | search for locations (15 files; a task with 29 comments) | 6 |
| C | a check of database index names at startup | 3 |
| D | waiting for the database at startup | 3 |
| E | validation of account input (11 files; planner runs only) | - |

**Cost.** *USD* is the price the Claude Code command line reports for a run. *Weighted units* count tokens with a weight per kind, which makes runs comparable across models and prices: input 1, writing to the cache 1.25 (kept 5 minutes) or 2 (kept 1 hour), reading from the cache 0.1, output 5. Weighted units give the same weight to every model, so a change of model is judged in USD.

The *cache* is a store of the already processed beginning of the prompt. A turn that starts with the same beginning pays 0.1 per token instead of 1, but pays it again on every turn, so a long conversation is mostly cache reads. That beginning is the *prompt prefix* (system prompt, tool definitions, instructions); it only counts as the same if it is identical byte for byte. The *start context* is everything a run is born with before the first turn of real work.

**Noise.** Each variant ran 3-12 times ("replicates") on the same task. **Replicates of the same variant differ by 15-40 %**, as much as most differences between variants, so a difference smaller than that is not reported as an effect, and the tables give ranges where it matters. Where a test is quoted, it is a permutation or Mann-Whitney test on the runs.

**Throwaway workspaces.** Each experiment ran in a scratch copy of the workspace (agent instructions and tool configuration) and in fresh clones of the repository. Each variant had its own CodeLoupe daemon on a separate home and port, so the author's real workspace was never touched.

The runs were non-interactive (`claude -p`, no person in the loop). Model and effort (how much the model reasons before it answers) were the same for both variants (Opus, high effort, unless a row says otherwise). The two variants ran at the same time where the machine allowed, never more than two runs at once. Opus is the larger Claude model, Sonnet the smaller and cheaper one.

**A correction.** On 2026-10-10 the way output tokens are counted from the logs was fixed: they had been read from the first line of a message instead of its last, which undercounted output by a factor of 2.1 overall (the logs of helper agents were affected). All experiments below were recomputed from the original logs.

No ratio and no conclusion changed, because the experiment runs are main sessions, where the first line was already complete. The shares in the next section use the corrected count.

## Experiments

### Where the cost goes

This is an analysis of the logs, not an experiment: about 2,400 runs of the author's workspace from 2026-09-23 to 10-06, with the corrected output count.

| Part of a run | Share of cost |
|---|---|
| Start context (system prompt, tool definitions, instructions, role prompt, task) | 16.5 % |
| Tool results (what the agent reads from tools) | 21.6 % |
| Output (thinking, tool-call arguments, text) | 15.7 % |
| The rest: the conversation's own growth (messages, edits, reports of helper agents) | about 46 % |

By kind of token, cache reads are 54 % of the cost. The split depends on the role: cache reads are 24 % of a reviewer run and 63 % of a coder run (planner 50 %), output is 33 % of a reviewer run and 17 % of a coder run (planner 23 %; from the ten days of 2026-10-01 to 10-10, 1,994 runs).

Cost therefore grows with the number of turns times the size of the context: each turn saved saves one more cache read of the whole context.

Reading code (file reads, shell searches, diffs) is a part of the tool results. It is 13-19 % of the cost in the four main roles (coder 13, reviewer 14, high-effort coder 14, planner 19 %) and 22 % in the deep reviewer.

### A shorter start

*What was tried:* each role keeps only the tool servers it uses, the project instructions file is not loaded into helper agents, and the descriptions of CodeLoupe's tools are shorter. (A tool server, or MCP server, is a program that gives the agent extra tools; its tool definitions go into every first prompt.) First-turn context, medians of 3-5 runs per role and variant on the same prompt:

| Change | Measured | Before | After |
|---|---|---:|---:|
| Roles keep only the tools they use (coder / reviewer / tester / planner) | first turn, thousand tokens | 18.4 / 25.0 / 17.3 / 26.4 | 11.0 / 10.6 / 10.0 / 12.1 |
| Project instructions file not loaded into helper agents | first turn, thousand tokens, all four roles | 20.3 / 19.9 / 19.3 / 21.4 | 11.0 / 10.6 / 10.0 / 12.1 |
| Session-start payload of the main session (27 sessions before, 5 after) | first turn, thousand tokens | 74.6 | 64.5 |

The roles still carry CodeLoupe's own tool definitions (1.5 to 2.5 thousand tokens per run, 0.65 % of total agent cost in the period measured). Shortening the descriptions of the nine code and document tools they use (7,931 to 5,656 characters; the whole default catalog of 14 tools, 13,329 to 10,837) took 424 (coder), 339 (tester), 558 (reviewer) and 633 (planner) tokens off the first turn, about 0.48 % of cost.

Nothing broke. On the two fixed reviews, 6 runs each per variant, the known findings were the same (4 of 4 and 6 of 6 in all 12 runs), the cost was within the spread (mean 1.27 against 1.23 USD) and 14 calls to the nine tools returned byte-equal answers.

Moving the project rules from the shared instructions file into the agent prompts lost no rule (54 of 54 rule questions answered correctly in both variants). None of the 16 runs that had the dropped tools back (two code-graph servers, the database client, three tracker reads) called one.

### Dropping two unused tool servers

*What was tried:* removing tool servers an agent never calls. 18 runs (reviewer on tasks A and B, planner on task B; 3 replicates): the first variant had a code-graph server and an IDE server enabled, the second had them removed.

| Role, task | Cost USD with / without (3 runs) | Units, thousand | Calls to the two servers | Known findings found |
|---|---|---|---|---|
| reviewer, A | 1.20 / 1.18 | 334 / 333 | 0 / 0 | 4 of 4 in all 6 |
| reviewer, B | 1.32 / 1.12 | 398 / 314 | 0 / 0 | 6 of 6 in all 6 |
| planner, B | 1.42 / 1.33 | 420 / 378 | 0 / 0 | n/a |

The variant with the servers cost 7-10 % more (spread inside a cell 0.84-1.55 USD), which matches its first call carrying 21.0 instead of 11.1 thousand tokens of tool definitions. In live use over the same period the two servers were called 0 times.

The IDE server could not be reached in the run (the IDE was closed), so its definitions were not measured; both servers were removed.

### A repository map at session start

*What was tried:* adding a map of the repository to the context a session starts with. 40 short read-only sessions (Sonnet; 10 questions about the code, each twice per variant), the full plugin, two daemons differing only in the map.

| | no map | map |
|---|---|---|
| context added at start | about 60 tokens | about 1,220 tokens |
| orientation calls (`ls`, `find`, `Glob`) per session | 0.25 | 0.15 |
| file reads / searches per session | 0.9 / 2.0 | 0.9 / 2.0 |
| turns per session | 3.4 | 3.3 |
| weighted cost per session, mean / median | 44,451 / 39,172 | 47,366 / 43,370 (+6.6 % / +10.7 %) |

The map was dearer in 7 of 10 questions and cheaper in 3. It stays off. In none of the 40 sessions did the model call a CodeLoupe tool.

### First comparison: reviewer and planner with the code index

*What was tried:* the reviewer and planner as they were before the code index against today's agents with the CodeLoupe tools. 24 runs, tasks A to E, one run per task and role (the reviewer twice on two of them); sums over the five tasks.

| Role | Variant | Cost USD | Units (thousand) | Model calls | Code reads | Index calls |
|---|---|---:|---:|---:|---:|---:|
| reviewer | before | 5.83 | 1,628 | 58 | 55 | 0 |
| reviewer | after | 4.89 | 1,352 | 48 | 40 | 1 |
| planner | before | 6.55 | 1,814 | 76 | 96 | 0 |
| planner | after | 6.39 | 1,828 | 102 | 101 | 10 |

The reviewer was 16 % cheaper (17 % in units), and the planner unchanged (units +1 %, model calls +34 %, wall time -20 %). **None of that can be credited to CodeLoupe**: the agents called it once in seven reviewer runs and ten times in five planner runs, and used `rg`, `git` and file reads for everything else.

What differs between the variants is mostly the agent instructions, which were rewritten in the same period. The next experiment shows what it took to get the tool used at all.

### Getting agents to use the code index

*What was tried:* one change at a time, 77 runs (10 variants, about 95 USD): a reviewer on four tasks with known findings (A to D) and a planner on three (A, B, E). The tools were not hidden from the agent and their descriptions were not the barrier.

The agent kept to `rg` in the shell (about 13 searches per run) out of habit. Its prompt named CodeLoupe in one bullet, and its list of allowed tools lacked `grep`. The plugin's *steering hook* (a script that points a shell search over source files at the equivalent CodeLoupe call) only advised: it spoke 6-8 times per run and 0 of 6 runs followed it. Reviewer, 4-10 runs per row:

| Change to the reviewer | Runs | Index calls per run (runs with at least one) | Units, thousand (median / mean) | USD | Known findings |
|---|---:|---|---|---:|---|
| none (the prompt of the day) | 10 | 0.2 (1 of 10) | 333 / 322 | 1.14 | 100 % |
| steering hook, advice only | 6 | 0.0 (0 of 6) | 350 / 338 | 1.20 | 100 % |
| `grep` allowed in the tool list, nothing else | 4 | 0.0 (0 of 4) | 373 / 366 | 1.27 | 100 % |
| routing table in the prompt, no `grep` | 4 | 2.8 (3 of 4) | 329 / 359 | 1.27 | 100 % |
| routing table and `grep` | 6 | 5.5 (5 of 6) | 314 / 315 | 1.12 | 100 % |
| steering hook that refuses the first shell search, `grep`, no table | 4 | 5.3 (4 of 4) | 318 / 320 | 1.14 | 100 % |
| table with a shortened last sentence | 6 | 1.5 (3 of 6) | 305 / 316 | 1.13 | 100 % |
| **final wording: table, `grep`, "`rg` only for non-Kotlin files"** | **10** | **6.0 (9 of 10)** | **323 / 331** | **1.17** | **100 %** |

A *routing table* is a table in the agent's prompt saying which tool answers which question. Adoption is decided by the wording of the prompt, not by availability: the table plus `grep` moved the reviewer from 0.2 to 6.0 calls per run and the planner from 2.3 to 8.0 (3 runs); a softer last sentence brought it back to 1.5.

**Cost did not move** (median 333 to 323 thousand units, mean 322 to 331, the range of one variant 229-483), and findings stayed complete in all 56 reviewer runs. The planner cost 445 thousand units before and 488 after (3 runs each, inside the noise).

The reason: of the 149 kB of tool results in a review run, 79 kB (53 %) is the prepared review packet, which the agent reads whole and no search replaces. Shell searches are 19 %, and only that part is open to CodeLoupe. How to set up your own agents: [Claude Code integration](Claude-Code-integration#your-own-agents-subagents-with-a-tools-list).

### A shorter reviewer prompt lost a check

*What was tried:* the reviewer's prompt was shortened (8.6 thousand characters instead of 13.9 thousand). The known findings of two tasks showed the cost: on task A it found 3 of 4 in both replicates, because it moved the check that a change comes with a matching pair of release-note entries to a reference file it never opened. Putting the check back, twice, 2 replicates each, same packet and clones:

| Reviewer prompt | Findings, task A (of 4) | Findings, task B (of 6) | Cost USD (A / B) | Model calls (A / B) |
|---|---|---|---|---|
| before the shortening | 4, 4 | 6, 6 | 1.20 / 1.18 | 11 / 13 |
| shortened | 3, 3 | 6, 6 | 0.86 / 1.34 | 7 / 15 |
| repair 1: a sentence saying the pair must be checked | 3, 3 | 6, 6 | 1.11-1.31 / 1.07-1.43 | 13-19 / 13-21 |
| repair 2: a missing pair is a finding, with a proposal | 4, 4 | 6, 6 | 1.39 and 0.98 / 1.59 and 1.27 | 26 and 12 / 25 and 17 |

The first repair was not enough: the reviewer mentioned the pair but listed it as "unknown, verify before landing". Only an instruction that makes the omission itself a finding brought the finding back, at a cost about a fifth higher than the shortened prompt (mean 1.31 against 1.07 USD; before the shortening 1.10).

The lesson for anyone trimming an agent prompt: re-run a fixed-findings check, because a cheaper run that finds less is not cheaper.

### A task pack that lets the planner stop

*What was tried:* the planner is the largest single cost of the flow (about a fifth of the flow's weighted cost in the period). In these runs 36 % of a run was output (thinking and the plan), 35 % cache writes and 29 % cache reads, with tool results only 20-30 %. So a bigger first answer pays only if it removes turns.

CodeLoupe's `task_context` tool now also carries the description sections of the task, the comment thread within a size budget (long comments cut in the middle, so a 29-comment thread keeps every decision), the files that reference the touched code, and the lines of the repository rules and documents that name the touched files. This bundle is the *task pack*.

The planner's instructions were changed to start from that pack, batch what it still has to read, and stop at a budget of 8 turns. 33 analysed runs (Opus, high; tasks A, B and E, each with a known real plan; 37 runs and 42.9 USD in total including 2 trial and 2 discarded runs):

| Variant | Runs | Units, thousand: median (range) | Mean USD | Model calls, median |
|---|---:|---|---:|---:|
| before: old instructions, old pack | 9 | 433 (255-506) | 1.45 | 38 |
| **new instructions, new pack** | 9 | **235 (198-259)** | 0.87 | 17 |
| old instructions, new pack | 9 | 398 (268-511) | 1.34 | 34 |
| instructions as deployed, new pack | 3 | 251 (179-281) | 0.89 | 16 |
| instructions as deployed, old pack | 3 | 285 (236-345) | 1.08 | 29 |

**The new variant costs 54 % of the old one** (median and mean; permutation test p = 0.0014; per task 49, 57 and 58 %). Cache reads fall by 81 % (fewer turns), cache writes by 37 %, output by 31 %; shell code reads per run go from 17.6 to 7.7.

**The pack alone saves 8 %**: with the old instructions the planner still reads the task again (1.8 calls per run) and the repository rules (1.1). So the other 46 points are the instructions telling it to start from the pack and stop.

Plan quality against the real plans is equal within the noise of nine runs: known review findings 27 of 30 in both, decisions of the real plan 98 % in both, files of the real solution 72 % against 77 %, premortem lines 16.4 against 18.1. ("Premortem" lines are the plan's list of ways the change could fail.) The one visible loss is on the smallest-diff task (files 82 % against 97 %), which the next experiment follows up.

The new variant marks 2.6 facts per plan as "unknown" with the step that resolves them instead of searching on.

Limits: 3 tasks, 3 runs per cell, no prepared context file (the live planner is given one, so part of the saving may already be taken), a pattern-based checklist, and a hazard found and fixed on the way: a fresh helper agent that read a root another agent had already read was told "unchanged since your read".

### One sentence about every file

*What was tried:* the planner skipped one-line changes and documentation, so its plans covered fewer of the files of the real solution on task E. Adding a section to the pack with files that earlier tasks changed together with the touched code named more files (5 to 7 of 11) but did not change the plans (0.84 against 0.85 of the real files, 5 runs each).

A sentence in the instructions did: "list every file the change touches, including one-line edits, the model and the documents the pack names". 21 runs (16 of them new) on task E and two further closed tasks (Opus, high):

| Instructions | Runs | Files of the real solution in the plan | Small files named | Units, thousand (median) | Mean USD |
|---|---:|---|---|---:|---:|
| from before the task pack | 6 | 39 of 42 = 0.93 | 15 of 18 | 240 | 0.88 |
| with the task pack | 9 | 67 of 75 = 0.89 | 21 of 27 | 200 | 0.76 |
| **with the pack and the sentence** | 6 | **41 of 42 = 0.98** | **18 of 18** | **173** | 0.68 |

Only task E tells the variants apart (the pack names the small files of the other two itself). The sentence missed none of them; the instructions without it missed them in 3 of 5 runs (6 of 6 against 9 of 15 small files).

That difference is **not statistically proven** (p about 0.29, two runs against five), but it goes the way the mechanism explains, and the sentence was never worse.

Cost against the instructions from before the pack: 72 % in median units (73 % mean; 77 % in USD); against the version without the sentence 87 %. Limits: two runs per cell on the new tasks, and closed tasks whose criteria sometimes already name files.

### Dispatcher: a local task index, then rewritten instructions

*What was tried:* the dispatcher read issues from the issue tracker's API; CodeLoupe's local task index answers those reads from a mirror. First the tools were swapped (28 runs, 47.65 USD, every plan passed the workspace's plan check, 16 of 16). Tracker calls per run fell from 39 to 0.2 (automatic choice) and from 29 to 0.3 (one epic), and one issue read is about half the size.

But **cost did not fall**: pooled over 8 runs per variant, the median went from 575 to 551 thousand units (-4 %; mean -14 %; p = 0.80), because the instructions still said to read each candidate (16.6 and 18.3 issue reads per run against 14.0 and 16.7 before). The planner was unchanged too (median 357 to 374 thousand, p = 0.48).

Then the instructions were rewritten in four intermediate versions (all of them 49-64 % of the original cost) and finally to read the candidate list once and filter with one query call instead of a separate task-list tool. Automatic choice, 5 runs per row:

| Dispatcher | Runs | Units, thousand (median) | Mean USD | Share of cost spent reading the tracker | Plan check |
|---|---:|---:|---:|---:|---|
| before the local index | 5 | 718 | 2.49 | 16.3 % | passes |
| local index only | 5 | 602 | 1.90 | 10.1 % | passes |
| **final instructions** | 5 | **302** | 1.20 | **4.0 %** | **5 of 5** |

The runs of the last row are from another day than the first, and the workspace changed in between (a rerun of the previous wording on the same day cost 325 thousand against its 481 of the earlier day). So the fair numbers are the same-day ones: final against previous wording 93 % (302 against 325, two runs), previous against before 67 %, which chains to about 62 %. Against the older run of another day it is 42 %.

The best intermediate wording, measured in one batch against the original, was 49 % of it (309 against 629 thousand units, 8 runs each, p = 0.003). Limits: 3-5 runs per cell, the plans are only checked by the plan check, not compared for which tasks they chose.

### A compact review packet

*What was tried:* the reviewer is given a prepared *packet* (the diff, the repository's rules index and, optionally, the changed declarations with callers and tests). Four closed review rounds with 16 known findings, 12 runs per variant (Opus, high): the full packet (about 78 kB: the diff with 12 lines of context), a compact one (40 kB: declarations with callers and tests, a diff with 3 lines of context, tests with 1) and a smaller one (25 kB: 2 lines of context, test and documentation diffs on demand with the command in the packet).

| Packet | Runs | Units, thousand (median / mean) | Median USD | Tool results kB | Known findings |
|---|---:|---|---:|---:|---|
| full (78 kB) | 12 | 323 / 324 | 1.13 | 147 | 48 of 48 |
| compact (40 kB) | 12 | 288 / 280 (89 % / 86 %) | 1.01 | 127 | 48 of 48 |
| **smaller (25 kB)** | 12 | **267 / 263 (83 % / 81 %)** | 0.96 | 114 | 48 of 48 |

A saving of 17 %, three points short of the 20 % target, with no finding lost. Per task the medians of the smaller packet are 56 to 88 % of the full one, and one cell spans 150-340 thousand, so three points are inside the noise of 12 runs.

The ceiling is structural: half of a reviewer's cost is cache writes of a context of about 80 thousand tokens, 28 % is output (thinking and the report), and the packet is only 12-13 thousand of those tokens. What the packet leaves out the reviewer reads on demand (code reads rose from 4.6 to 7.4 % of cost).

The smaller packet was adopted anyway (low regret, reversible): the packet of a large closed task shrank from 116 to 45 kB.

### Coder: a routing table and a steering hook

*What was tried:* the same two levers as for the reviewer, aimed at the coder. Three closed tasks with a known solution (a command-line option change, a refusal in a weekly import, a nullability change across three modules); 15 and 9 runs per variant (Sonnet, medium effort). After each run the known solution's tests were laid over the coder's tree and the targeted check ran again.

| Coder | Runs | Units, thousand (median / mean) | Model calls | Shell searches per run, share of cost | Known-solution checks |
|---|---:|---|---:|---|---|
| today, plan with line hints | 15 | 80 / 84 | 11.3 | 1.5, 4.2 % | 15 of 15 pass |
| **table, `grep`, steering hook that refuses the first search** | 15 | **103 / 105 (129 %)** | 13.7 | 2.5, 1.3 % | 15 of 15 |
| today, issue only (no plan) | 9 | 70 / 73 | 9.6 | 2.9, 7.3 % | 9 of 9 |
| **table, `grep`, hook** | 9 | **81 / 82 (115 %)** | 11.2 | 3.4, 5.1 % | 6 of 9 (one implementation-specific assertion in one task) |

The target was 85 % of today's cost; the variant costs more. Shell search is only 4-7 % of a coder run here, so there is little to save, and the table moves part of it to `outline` and `grep` calls that add two turns. The hook spoke in 4 of 15 runs (10 times) and in none of the 9 runs without a plan. Rejected.

### Coder: a terse-output rule

*What was tried:* one added sentence in the coder's prompt: "terse: no recap, no restating the task or plan, no narration between tool calls, the result in the first line". 3 tasks with a known solution, 3 replicates, 2 variants (Sonnet, medium), 3.20 USD.

| Coder | Runs | Units, thousand (median / mean) | Output tokens (mean) | Final message (characters) | Model calls | Known-solution tests |
|---|---:|---|---:|---:|---:|---|
| today | 9 | 94 / 92 | 5,973 | 2,166 | 17 | 9 of 9 |
| with the rule | 9 | 79 / 85 (84 % / 92 %) | 5,691 (-5 %) | 2,183 | 14 | 8 of 9 (one run did not compile) |

The spread of replicates of one task is 65-123 thousand, so the difference is noise, and the report did not get shorter: the coder already writes in a compact notation. Most of an agent's output is thinking (reviewer 66 %) or tool-call arguments (coder 74 %), not prose.

The rule was not adopted. For the tester the logs bound the saving at about 4 % of its cost, far below the noise of nine runs, so it was not tried.

### A cheaper model or lower effort for the planner and reviewer

*What was tried:* the planner and the reviewer (Opus, high effort) are together over half of the spend of the flow. The same scripts, prompts, packets and clones as in the two experiments above, only the model and effort change; the Opus rows are the earlier runs, not rerun. 25 new runs, 10.9 USD. The cost criterion (at most 60 % of Opus high) is judged on USD.

| Role | Variant | Runs | Units, thousand (median) | USD, median / mean | Cost against Opus high (USD / units) | Quality |
|---|---|---:|---:|---|---|---|
| reviewer | Opus high | 12 | 267 | 0.97 / 0.94 | 100 % | all 48 known findings |
| reviewer | **Sonnet high** | 8 | 156 | 0.31 / 0.31 | **32 % / 58 %** | **misses 2 of 3 known findings on task C, in 2 of 2 runs** (28 of 32 overall) |
| reviewer | **Opus medium** | 8 | 168 | 0.62 / 0.64 | **64 % / 63 %** (mean 68 %) | all 32 known findings |
| planner | Opus high | 12 | 240 | 0.87 (mean) | 100 % | findings 0.93, decisions 0.99, files 0.74, 16.4 premortem lines, 7.4 lenses |
| planner | **Sonnet high** | 9 | 184 | 0.36 (mean) | **41 % / 77 %** | findings 0.90, decisions 0.97, files **0.67**, premortem lines **12.1**, lenses **4.7** |

**Neither Sonnet variant passes.** Sonnet is cheap on the reviewer (a third of the cost, 7 turns instead of 12) but reports the headline defect and drops the secondary ones. Counting words over the whole report would have called that 100 %, because the words reappear in the "verified" section, so the findings are also counted inside the Findings section only (the Opus runs score the same either way).

Opus medium keeps every finding but saves 36 %, four points short of the 40 % target, and falls short on each of the four tasks (61-76 % of the cost).

The Sonnet planner is a third of the cost per plan but plans in 8 turns instead of 16. It writes 26 % fewer premortem lines and a third fewer risk lenses (kinds of risk considered), and covers 7 points fewer of the real solution's files, in all three tasks; the decisions and known findings stay within the noise. Sonnet at medium effort for the planner was not run, since high already loses the premortem.

Nothing was changed in the live agents. Limits: 2-3 replicates per cell, patterns over the text of the report as the quality measure (not graded independently, false findings not counted), one set of closed tasks.

### Waiting for long commands

*What was tried:* turns whose only call is waiting (`sleep`, a status poll, `job wait`) were 9.1 % of all agent cost in 2,651 live runs. The controlled task: a coder agent compiles three clean clones one after another, read-only. *Before:* long commands run in the background and are polled with `sleep` every few minutes. *Two calls:* `job start`, then `job wait`, through the CodeLoupe daemon. *One call:* `job start --wait`, which starts the job and blocks until it ends.

Two command lengths, 3 replicates each, 18 runs; waiting turns and units are sums over the replicates, the other columns are per run.

| Command length | Variant | Model calls per run | Waiting turns | Waiting share of units | Units per run (thousand) | USD per run |
|---|---|---:|---:|---:|---:|---:|
| about 3 min | before | 6.7 | 8 | 32.2 % | 29.2 | 0.058 |
| about 3 min | two calls | 12.0 | 12 | 20.6 % | 45.8 | 0.092 |
| about 3 min | **one call** | 4.0 | 0 | 0 % | 12.8 | 0.026 |
| 4-6 min | before | 11.7 | 14 | 39.9 % | 39.5 | 0.079 |
| 4-6 min | two calls | 10.3 | 12 | 27.1 % | 33.9 | 0.068 |
| 4-6 min | **one call** | 4.0 | 0 | 0 % | 23.4 | 0.047 |

The two-call variant first looked like a failure: its waiting units per run against the variant before were 1.00 at 3 minutes and 0.58 at 4-5 minutes, which on the live 9.1 % is about 5.3 %, not the target of 1 %. The runs showed why.

A non-interactive `claude -p` waits in the foreground, so every long command costs one waiting turn however it is waited for. And in five of six runs of the two-call variant the agent wrote its first `job start` with a doubled command prefix and paid for a second start and a second wait.

The one-call variant fixes both (a repeated leading command is dropped with a one-line note instead of an error). In all 6 runs no turn was only waiting, all 18 starts were correct, and a run took 4 model calls (3 blocking compiles and the report) against 10-15.

**Waiting did not become free**: the blocking call is still one model call per long command, so 0 waiting turns is a floor of one call, not zero cost. The cost differences between the variants also carry the replicate spread (the three long runs of the one-call variant cost 0.056, 0.056 and 0.029 USD). Not covered: commands over about 9 minutes (one more call), waiting for a free build slot, and interactive sessions.

### Fewer turns per run: a batch tool

*What was tried:* a tool that answers up to 8 independent read-only calls (symbol, usages, search, document sections and so on) in one turn, each part labelled, an error staying in its own part. The agents' instructions already said to put independent reads in one message, yet only about a third of their read turns were parallel. The tool is off by default (it adds about 770 characters, 7 %, to the tool list) and was switched on for the second variant together with one sentence in the instructions of the coder, reviewer and planner. 36 runs, 22.4 USD in total: coder (Sonnet, medium; three tasks, each with a known solution whose tests were run on the result), reviewer (Opus, high; two tasks) and planner (Opus, high; one task), 3 replicates per task and variant, the two variants run side by side.

| Role | Runs per variant | Weighted units, thousand (sum) | USD (sum) | Turns per run | Used the tool | Quality |
|---|---:|---|---:|---|---|---|
| coder, today | 9 | 762 | 1.52 | 12.4 | - | 9 of 9 pass the known tests |
| **coder, batch** | 9 | 802 (+5 %) | 1.60 | 13.0 | **0 of 9 runs** | 7 of 9 (two runs of one task fail one assertion of the known tests) |
| reviewer, today | 6 | 1,789 | 6.33 | 11.3 | - | 30 of 30 known findings |
| **reviewer, batch** | 6 | 1,948 (+9 %) | 6.84 | 12.3 | 6 of 6 runs, 3.2 calls of 5.0 parts | 30 of 30 |
| planner, today | 3 | 897 | 3.36 | 10.3 | - | 50 % of the real solution's files named |
| **planner, batch** | 3 | 734 (-18 %) | 2.78 | 8.3 | 3 of 3 runs, 5.3 calls of 6.6 parts | 54 % |

Pooled over all 18 pairs, each run divided by the median of its task's current-version runs: median 0.97, mean 1.05; the goal was a median of 0.90 or less. Total USD 11.22 against 11.21.

- **Where it helped:** the planner, which reads many declarations in a row: it used the tool in every run and needed 2 turns fewer (a repeat on three tasks, below, did not confirm a saving). Its current instructions already made 93 % of its read turns parallel, so the saving is the difference between several parallel calls and one, not a new kind of parallelism.
- **Where it did not:** the reviewer used the tool but also kept issuing single reads afterwards (turns 11.3 to 12.3), and one task cost 16 % more; the coder, a smaller model, never called it in 9 runs, so its numbers are run-to-run spread. Parallel read turns went from 34 % to 31 % of read turns.
- **Limits:** 3 replicates per cell, one planner task, the tool and its sentence were measured together, and a failed assertion of the known tests is an implementation difference that the tool cannot have caused (it was unused). Not adopted in the live agents; the tool stays in the product as an option (`tools.batch`).

**Planner only, repeated on three tasks.** Since an agent only gets the tools its own definition lists, the tool could be switched on for the planner alone without costing the other roles anything. The planner (Opus, high) was run again on three tasks (the earlier one and two more), 3 replicates per task and variant, 18 runs, 16.6 USD (24 runs would have cost about 22 USD, over the budget). Today's instructions against the same instructions with the tool and its sentence:

| Task | Weighted units, thousand (median) today -> batch | Ratio | USD (median) |
|---|---|---:|---|
| first | 299 -> 291 | 97 % | 1.11 -> 1.12 |
| second | 196 -> 210 | 107 % | 0.73 -> 0.78 |
| third | 258 -> 235 | 91 % | 0.96 -> 0.90 |

Pooled, each run divided by the median of its task's current-version runs: median 0.97, mean 0.98 (a rough 95 % interval for the median is 0.88-1.07); the goal was 0.90 or less. USD 8.27 against 8.32. The planner used the tool in 9 of 9 runs (4.7 calls of 6.4 parts, no failed part) and made fewer turns (model messages 9.9 to 8.4, parallel read turns 72 % to 84 %), but the cost did not follow: about 44 % of a run's weighted units is the written plan (21,800 output tokens either way), and a saved turn only saves a cheap cache read. Against the real plan's checklist, known findings were 28 of 30 against 30 of 30, decisions 60 of 60 either way, and the real solution's files named in 73 % of the plans against 80 % (the plans with the tool lost the same few files in all three runs of a task). The first experiment's -18 % for the planner came from one task whose ratio is 97 % here, so it was spread. Not adopted; no live instructions or settings changed, and the option stays available (`tools.batch`) for anyone who wants it.

### Cache kept 5 minutes instead of 1 hour

*What was tried (an analysis of 1,994 runs of ten days, 1.99 billion weighted units; the flow's own roles are 46 % of them):* the agents asked the provider to keep the processed prompt for 1 hour, which costs 2 per token to write instead of 1.25 for 5 minutes. For most helper agents the premium is not returned.

Over the period the premium was 79.7 million units for helper agents, against 23.5 million of rewrites avoided after pauses of 5-60 minutes plus 12.4 million of prefix reuse at start.

| Role | Premium paid (million units) | Returned (million units) | Decision |
|---|---:|---:|---|
| reviewer | 25.4 | 2.9 | 5 minutes |
| planner | 11.2 | 1.9 | 5 minutes, next |
| nightly summary agent (writes a summary of the day's closed tasks) | 3.6 | 0.8 | 5 minutes |
| deep reviewer | 1.1 | 0.05 | 5 minutes |
| coder | 7.9 | 8.0 | stays 1 hour |
| tester | 7.8 | 9.4 | stays 1 hour |

The reviewer, the nightly summary agent and the deep reviewer now ask for 5 minutes, per agent (about 3 % of the flow's cost). The main thread of a run launched for one phase of a task ignores the setting and keeps 1 hour, which its resumed rounds need (35 resumed rounds read 6.4 million tokens from the cache against 68 thousand written).

The result depends on the gap between turns: the coder, the tester and a high-effort coder stay on 1 hour because their gaps of 180-300 seconds decide it.

### A stable prompt prefix

*What was found (analysis of the same logs):* the system prompt of consecutive runs of one role is identical in 89-100 % of cases, and yet every run wrote 11.5-15 thousand tokens fresh to the cache at its start.

One cause was a block that a startup hook put in front of the stable part, a list of the other open windows that changes with every task claim (different in 117 of 133 consecutive runs of one role). The hook now stays out of non-interactive runs, which saves about 2.3 thousand units per start (0.4 % of a run).

The whole instability, if every start were byte-identical, could be worth at most 1.2 % of the cost; the rest was not found in the logs and needs the request itself to be captured. A second block, a timestamp in a prepared context, sits in a tool result, not in the shared prefix, and costs nothing.

### Capping large tool results

*What was found (same logs, no experiment):* the cost of large tool results is spread thinly. The 20 largest results of a role carry only 0.5-1.3 % of its cost (4.5-6.8 % in the small roles); a cap of 12 thousand characters would save 1-4 % and lose information.

Reading a whole large source file is the biggest class (reviewer 2.6 % of its cost, high-effort coder 2.7 %, planner 2.6 %). One role, the nightly summary agent, spent 21.6 % of its cost on whole-file reads of notes of 5-77 thousand characters, and got a one-line rule against it. The rest was left alone.

### A warm session pool

*What was simulated (no runs):* keep one worker session per role alive and give the next run of that role a warm start. The saving of a reuse is 21-28 thousand units (planner 40), but every later turn pays 0.1 per token of the accumulated context, and roles with long runs add 111 (coder) or 178 (reviewer) thousand tokens per run.

Result over several reset sizes and cache lifetimes: the two smallest roles (a bookkeeping agent and the tester) gain 3-15 %; the larger ones lose from 0 to +39 %. In total the best policy is -0.1 %, and 200 thousand tokens of reset with a 60-minute cache is +6.5 %, 600 thousand +31 %.

A pool is not built. What it would have bought is the same as a stable prefix, without the sessions.

### Ideas dropped before they were built

The logs showed a ceiling under 1 % of cost for these, so they were not built: a rule checker for review findings (only 24 of 503 recorded findings, 4.8 %, and 2 of 81 serious ones could be found by a rule), generating file skeletons (0.19 %), a classifier for environmental failures such as port clashes (0.55 %), a deterministic nightly summary (at most 0.15 %), a hook against repeated reads (5 repeats in 25,653 reads), a warm session pool (0.1 %) and a cap on large results (1-4 % with lost information).

## Limits that apply to all of it

- **Wall time is only indicative:** two runs at a time on a busy machine.
- **Live use is not the experiment.** The runs are non-interactive, with hooks off as in a scratch workspace, and without the prepared context some live agents get. Absolute numbers move from day to day with the state of the workspace, so only same-day comparisons are fair.
- **The tasks were closed ones,** so their comment threads also contain findings of later review rounds.
- **The experiment scripts are not published yet.** The numbers cannot be reproduced from this repository today; the method is described above, and the scripts will be published when they are cleaned of the author's workflow.

Back to the short page: [Agent runs](Agent-runs).
