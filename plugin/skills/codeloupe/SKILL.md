---
name: codeloupe
description: Use the CodeLoupe MCP tools instead of grep and whole-file reads when you need a declaration, a file or type outline, usages, callers, a type hierarchy, what a branch changed, which code a task touched, tracker issues and tasks, or a long build/test run. Covers Kotlin code in any git repository.
---

# CodeLoupe

A local daemon indexes the repository (and every worktree of it) and answers with exactly the piece of code asked for.
Code tools take `root`: the absolute path of the repository or worktree you work in (`job` takes `cwd`). Names are
`Type`, `Type.member`, `member(ParamType)`, `pkg.Type` or `File.kt:line`; globs (`*Repository`) work in `find`.

## Which tool when

| You want | Use | Instead of |
|---|---|---|
| Where is `X` declared | `find` (`q` = name, `Type.member` or glob; `kind`, `module`, `test` narrow) | `grep -r "class X"` |
| A string literal, SQL, annotation argument or config key written in code | `grep` (`pattern`, literal unless `regex=true`; hits grouped by enclosing declaration) | `rg` and reading around each hit |
| What is in this file or type | `outline` (`target` = path or type; members with line ranges, no bodies) | reading the file |
| The source of one declaration | `symbol` (`name`; KDoc, annotations, body; a large type collapses to header + members) | reading the file and scrolling |
| Before changing `X`: its source, callers and callees | `context` (`name`; one call instead of `symbol` + `calls` twice) | three separate calls |
| Who uses `X` | `usages` (`name`; exact `=` and candidate `?` hits grouped by enclosing declaration; `all=true` adds references that resolve elsewhere) | `grep -w` |
| Who calls `f`, or what `f` calls | `calls` (`name`, `direction` = `callers` or `callees`, `depth` ≤ 3) | chains of greps |
| Supertypes, subtypes, overrides | `hierarchy` (`name`) | grepping `: Type` |
| What did my branch change | `changes` (by declaration against the merge-base, with callers and tests; `bodies=true` for line diffs) | `git diff` of whole files |
| Which task touched this code, or which code a task touched | `task_code` (`query` = a task id, a declaration or a path) | `git log --grep` and guessing |
| Read an issue | `issue` (`id`; `view=brief` first, then `sections=[…]`) | opening the tracker |
| Find or plan tasks | `tasks` (`mode=list` with a query, `graph`, `ready`, `progress`) | tracker search by hand |
| Change an issue's state or add a comment | `update` (`id`, `set={State: …}`, `comment`) | the tracker's UI |
| A test or build that takes a while | `job` (`action=start`, `command` = argv array, `cwd`, `slot` for shared resources; then `action=status`) | a blocking shell call |

`issue`, `tasks` and `update` work only when a tracker is configured for the daemon; `task_code` also works without one
(it reads the default branch's history).

## Working rules

- Start with `outline` or `find`, then `symbol` for the one declaration you need; read a whole file only when the index
  cannot answer (non-Kotlin files, configuration, text).
- `usages` marks unsure hits with `?` instead of dropping them. A name shared by unrelated declarations must be
  qualified (`Type.member`) or the answer is a superset.
- The index follows the working tree: edits in a worktree show up on the next query, no refresh step.
- A repeated `issue` read from the same `root` answers `unchanged since …` or only the difference.
- Long commands go to `job`: start it, end the turn when there is nothing else to do, and read `status` when you
  continue. Do not hold a turn open on a slow build.

## If the tools fail

- `connection refused` / the server shows as failed: the daemon is not running. Run `codeloupe start` (the plugin
  tries this at session start; it needs `codeloupe` on PATH or `CODELOUPE_BIN`), then reconnect the MCP server with `/mcp`.
- A different port: set `CODELOUPE_PORT` for the daemon and for Claude Code alike.
