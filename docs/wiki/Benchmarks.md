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
| Subtypes of a type | `hierarchy` | 127 | 464 | 102 |
| Text search, 30 hits | `grep` | 1,298 | n/a | 1,497 |
| What a branch changed | `changes` | 2,938 | 79,728 | 2,247 |

*grep + read* is what an agent without an index typically does: `rg` with context lines, a whole-file read, the full
`git diff`. *grep, minimal* is a best case that assumes the agent never reads a line it does not need (for source
lookups it is given the exact line range, for the branch `git diff --stat`). Against grep + read, CodeLoupe's answers
are 4–41 % of the size (8 % summed over all questions) and take one call where grep needs two for source lookups.
Against the best case they are about the same for source lookups and outlines, smaller for usages and callers, and
larger for subtypes and branch changes: CodeLoupe returns more per line (the type itself and its direct supertypes, the
enclosing declaration of every hit, exact against candidate marks, callers and tests of every changed declaration
that was not added), and `git diff --stat` says less.

Beyond navigation, and not part of the benchmark: worktrees of one repository share one index and each adds only its own
edits; long commands run in the daemon so an agent's turn can end ([Jobs and events](Jobs-and-events)); a tracker mirror
answers issue reads locally; `codeloupe metrics` shows from Claude Code transcripts where an agent's context goes
([Measuring agent runs](Metrics-and-savings)).

## Method and results against GitNexus

`node tools/benchmark.mjs` asks the same questions of a grep-and-read baseline, CodeLoupe and
[GitNexus](https://github.com/abhigyanpatwari/GitNexus) over public repositories at pinned commits
(`JetBrains/Exposed` `023a6a3`, 319 Kotlin files without tests; CodeLoupe `f370022`, 457 files), and writes
[docs/benchmarks.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/benchmarks.md), [docs/benchmarks.json](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/benchmarks.json) and the chart below. Run of
2026-10-08 on Windows 11, i7-13700F, 64 GB, CodeLoupe 0.1.0 (commit `b2a695e`), `gitnexus@1.6.12` from npm.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Terrio-cz/CodeLoupe/main/docs/benchmarks-dark.svg">
  <img alt="Median tokens read per question" src="https://raw.githubusercontent.com/Terrio-cz/CodeLoupe/main/docs/benchmarks.svg" width="830">
</picture>

Tokens read per question, median over 15–16 questions of each kind (2 for the branch, 6 for text search):

| Task | CodeLoupe | GitNexus | grep + read | grep, minimal |
|---|---:|---:|---:|---:|
| Read a type | 326 | 2,021 | 789 | 322 |
| Read a member | 117 | 938 | 1,746 | 118 |
| Outline of a file | 206 | n/a | 615 | 197 |
| Who uses a type | 560 | 1,601 | 2,997 | 626 |
| Who calls a member | 169 | 726 | 1,352 | 286 |
| Subtypes of a type | 127 | 1,412 | 464 | 102 |
| Text search, 30 hits | 1,298 | n/a | n/a | 1,497 |
| What a branch changed | 2,938 | 21,769 | 79,728 | 2,247 |

GitNexus 1.6.12 has no outline or text-search tool, so those rows are `n/a`. Its `context` answers are JSON cards (callers,
callees, process membership), not the same content as CodeLoupe's, so the comparison is of what is read, not of what is
learned. Both tools answered every question they have a tool for; for 8 of GitNexus's 82 answers (names that exist more
than once) it needed a second call with the file path, and both answers are counted.

### Cost of having the tool

Same run:

| | CodeLoupe | GitNexus |
|---|---:|---:|
| Tool definitions in the agent's context (`tools/list`) | 13 tools, 3,749 tokens | 17 tools, 22,140 tokens |
| First index, Exposed / CodeLoupe | 7.3 s / 2.7 s | 128 s / 68 s |
| Peak memory while indexing, Exposed | 517 MB | 2,583 MB |
| Memory after the queries | 206 MB daemon, both repositories | 3,325 MB MCP server after 82 queries (114 MB at start) |
| CPU while idle, 30 s | 0 ms | 15 ms |
| Warm call (median, per question kind) | 10–11 ms; 26 ms text search; 750 ms branch changes | 155–213 ms; 214 ms branch changes |
| Index on disk, Exposed / CodeLoupe | 62 MB for both | 1,067 MB / 141 MB |
| Files written into your checkout by indexing | 0 | 8 (`AGENTS.md`, `CLAUDE.md`, `.claude/skills/`; off with `--skip-agents-md` and `--skip-skills`) |

### What the numbers do not show

- They count what an agent reads, not whether it finishes a task; the controlled agent benchmark of
  [docs/plan.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/plan.md) § 8.4 has not been run.
- One run on a shared workstation: token counts are deterministic, timings and memory are indicative and vary between
  runs.
- Two repositories, one of them CodeLoupe's own, questions chosen by a fixed rule that favours grep (names unique in the
  repository). GitNexus's default database buffer pool (428 MiB here) was too small to index Exposed; the benchmark sets
  2 GiB for `analyze`, as the error message suggests. GitNexus's worktree handling and newer builds than 1.6.12 were not
  measured. An IDE-based MCP server cannot be started by a script and is not measured.
