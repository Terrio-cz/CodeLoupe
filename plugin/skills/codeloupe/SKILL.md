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
| Which declaration handles a concept you have no name for ("token limit", "retry after failed delivery") | `find` with `mode=search` (`q` = the words, also when `q` has spaces; ranked, top 10, with the words each hit matched) | chains of `grep` guesses |
| A string literal, SQL, annotation argument or config key written in code | `grep` (`pattern`, literal unless `regex=true`; hits grouped by enclosing declaration) | `rg` and reading around each hit |
| Orientation in a repository you do not know | `outline` without `target` (ranked map of files and types within `budget` tokens; `focus` = the files or symbols you work on) | `ls`, `find`, reading directory after directory |
| What is in this file or type | `outline` (`target` = path or type; members with line ranges, no bodies) | reading the file |
| The source of one declaration | `symbol` (`name`; KDoc, annotations, body; a large type collapses to header + members) | reading the file and scrolling |
| Before changing `X`: its source, callers and callees | `context` (`name`; one call instead of `symbol` + `calls` twice) | three separate calls |
| Who uses `X` | `usages` (`name`; exact `=` and candidate `?` hits grouped by enclosing declaration; `all=true` adds references that resolve elsewhere) | `grep -w` |
| Who calls `f`, or what `f` calls | `calls` (`name`, `direction` = `callers` or `callees`, `depth` ≤ 3) | chains of greps |
| Supertypes, subtypes, overrides | `hierarchy` (`name`) | grepping `: Type` |
| What did my branch change | `changes` (by declaration against the merge-base, with callers and tests; `bodies=true` for line diffs) | `git diff` of whole files |
| Which task touched this code, or which code a task touched | `task_code` (`query` = a task id, a declaration or a path) | `git log --grep` and guessing |
| Read an issue | `issue` (`id`; `view=brief` first, then `sections=[…]`) | opening the tracker |
| Start planning a task: issue, linked tasks, open criteria, touched code, earlier tasks on the same files | `task_context` (`id`; one call instead of `issue` + `tasks` + `task_code` + search; `sections=[…]` to pick) | four calls and a search |
| Decide which tasks run in which window without two windows on the same code | `dispatch_plan` (`candidates`, `epic` or `query`, `slots`; windows with the shared code, per-task keys, what waits and why) | predicting collisions by hand with grep |
| Read a plan, a brain note or a large saved tool output | `doc` (`path`; digest first, then `section=[handle or L120-160]`; `view=full` for all) | reading the whole file again |
| Find or plan tasks | `tasks` (`mode=list` with a query, `graph`, `ready`, `progress`) | tracker search by hand |
| Before creating an issue | `similar` (`summary`, `description`: tasks that already talk about it; extend or link one instead of a duplicate) | creating first, finding the duplicate later |
| Change one declaration, add a member or an import, delete a declaration, rename a symbol (only when `edit` is on offer) | `edit` (`op` = `replace`, `insert_after`, `insert_before`, `insert_member`, `delete`, `add_imports`, `create_file`, `rename`; `name` as for `symbol`, `hash` = the `hash=` it printed; `rename` with `dry_run=true` first) | `Edit` with the old text, search and replace over files |
| Change an issue's state or add a comment | `update` (`id`, `set={State: …}`, `comment`) | the tracker's UI |
| `git status` / `git log` / `git diff --stat`, a quick gradle build or test run, any CLI with a long output | `run` (`command` = argv; a summary with every error line and a handle `job:<id>`; `doc path=job:<id>` reads the rest; `raw=true` for the whole output) | the raw Bash output |
| Which environment variables or secrets a workspace has | `env` (names, scopes, last use — never a value; start a process with them via `codeloupe env run -- <cmd>`) | reading `.env` files |
| A test or build that takes a while | `job` (`action=start`, `command` = argv array, `cwd`, `slot` for shared resources; then `action=status`) | a blocking shell call |

`edit` is on offer only where the write policy allows it (`write.mode` in the daemon's `config.json`); read the declaration with `symbol` first, pass its `hash`, and read it again after a refusal. Claude Code's own `Edit` refuses a file that changed since it was read: after an `edit` write, `Read` the file again before using `Edit` on it.

`issue`, `task_context`, `dispatch_plan`, `tasks`, `similar` and `update` work only when a tracker is configured for the daemon; `task_code` also works without one
(it reads the default branch's history), and `doc` needs no repository or tracker.

## Working rules

- Start with `outline` or `find`, then `symbol` for the one declaration you need; read a whole file only when the index
  cannot answer (non-Kotlin files, configuration, text).
- `usages` marks unsure hits with `?` instead of dropping them. A name shared by unrelated declarations must be
  qualified (`Type.member`) or the answer is a superset.
- The index follows the working tree: edits in a worktree show up on the next query, no refresh step.
- A repeated `issue`, `task_context` or `doc` read from the same `root` answers `unchanged since …` or only the difference;
  `since=none` sends it again (do that after the context was cleared). Read a large file with `doc` digest first, then only the sections you need.
- Long commands go to `job`: start it, end the turn when there is nothing else to do, and read `status` when you
  continue. Do not hold a turn open on a slow build.

## Hooks of the plugin

After a shell search (`rg`, `grep`, `find -name`, `git grep`) or a read of a whole indexed source file (`cat`, `head`, `sed -n`, `Read`
without `offset`/`limit`, 150 lines or more) a `PreToolUse` hook may add one line naming the CodeLoupe call that answers the same
question (`usages`, `find`, `grep`, `outline`). Use it instead of reading the output you were about to get. In `redirect` mode the
first attempt is refused with that line: run the same command again when the index cannot answer. The hook speaks once per command,
never for documents, short files, builds, git or a repository the daemon has not indexed. It is switched off with
`"hooks": { "enabled": false }` in the daemon's `config.json` (`"steer": { "mode": "off" }` for this hook alone) or
`CODELOUPE_HOOKS=off` in Claude Code's environment; `codeloupe metrics hooks` counts what it said and what was followed.

A session in an indexed repository may begin with a line `CodeLoupe orientation for …` (branch, task, what the worktree changed, and with
`sessionStart.map` the ranked repository map): start from it instead of `ls`, `find` or `git status`, and ask `outline`/`find`/`symbol` for the rest.

## If the tools fail

- `connection refused` / the server shows as failed: the daemon is not running. Run `codeloupe start` (the plugin
  tries this at session start; it needs `codeloupe` on PATH or `CODELOUPE_BIN`), then reconnect the MCP server with `/mcp`.
- A different port: set `CODELOUPE_PORT` for the daemon and for Claude Code alike.
