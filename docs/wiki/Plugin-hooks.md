The plugin registers two hooks: `SessionStart` starts the daemon ([Hooks and token savings](Hooks-and-token-savings#the-sessionstart-hook)), and `PreToolUse` steers searches and whole-file reads, described here.

Agents still reach for `rg`, `grep`, `cat` and whole-file reads. A hook runs outside the model and costs no tokens, so the
plugin points those calls at the CodeLoupe call that answers them. All hooks go through one script (`plugin/hooks/hook.sh`:
`curl` to `POST /hook` of the local daemon, a few hundred microseconds of work in the daemon) and fail open: no daemon, no `curl`,
a daemon that does not know the repository, a malformed input or a timeout all mean "say nothing", never an error.

**Steering** (`PreToolUse` on `Bash`, `PowerShell` and `Read`). It looks at the call:

| Call | Answer |
|---|---|
| `rg`/`grep`/`git grep`/`Select-String` for `class Foo`, `fun bar` | `find q="Foo"` (with `kind`) |
| the same for a name (`OrderService`, `parseConfig(`, `MAX_SIZE`) | `usages name="OrderService"` |
| the same for a string, SQL or a regular expression | `grep pattern="…"` (`regex=true`, `ignoreCase=true`) |
| `find -name '*Service.kt'`, `rg --files -g '*.kt'` | `find q="*Service"`, or `outline` (the repository map) for every Kotlin file |
| `cat`, `bat`, `head -n 300`, `tail -n +50`, `sed -n '1,400p'`, `Get-Content` of a source file of 150 lines or more, and `Read` of one without `offset`/`limit` | `outline target="path/File.kt"`, then `symbol` for one declaration |

It stays out of the way when the daemon is not running or has not indexed the repository, when the file is not in the index
or has fewer lines than `minLines`, when the command is not about Kotlin or Java source (builds, git, `.md`/`.json` files, a
search of a pipe's output, a read cut short by `head`/`grep`), when a read has `offset` or `limit`, and when the same command
comes again in the session (the agent insisted). A session gets at most `maxPerSession` pieces of advice, and none after `giveUpAfter` in
a row without a CodeLoupe call on that repository in between: an agent that cannot or will not use the tools is not nagged, and one that does use them is advised again.

Modes, in `<home>/config.json` (read on every call, no restart):

```json
{ "hooks": { "enabled": true, "steer": { "mode": "advise", "minLines": 150, "maxPerSession": 40, "giveUpAfter": 4 },
             "sessionStart": { "enabled": true, "map": false, "budget": 1200, "changes": true, "changesLimit": 12 } } }
```

- `advise` (default): the command runs and the model reads the equivalent call next to its result (`additionalContext`; permissions are not touched).
- `redirect`: a command that plainly concerns source files (a `*.kt` glob, `--type kotlin`, a source file as target) is refused the first time with the equivalent call; the same command again runs. Other commands are only advised.
- `off`: no steering. `"hooks": { "enabled": false }` (or `"hooks": false`) turns off every hook of the plugin at once, and so does `CODELOUPE_HOOKS=off` in the environment of Claude Code.

`codeloupe metrics hooks --since 2026-10-01` counts the advice from `<home>/hooks.jsonl` (kind, suggested tool, session prefix, no
command text) and how many were followed by a CodeLoupe code call on the same worktree within 180 s of the advice (`calls.jsonl`);
`/status` has `hooks` (calls, advised, denied, why the rest was left alone, median and p95 ms of the decision).
`codeloupe metrics hooks --replay --since 2026-10-01` runs the shell and read calls of old transcripts through the same
decision and prints how many it would advise and with which call (counts only; sizes from the transcript's own results).

## Session start

The `SessionStart` hook (`startup`, `resume`, `clear`, `compact`) starts the daemon as before and then asks it for the context a session
begins with, so the first turns need not go to `ls`, `find` and `git status`. It speaks only for a git repository the daemon has already
indexed (it never starts a build), and says nothing otherwise.

- **State of the worktree** (always, when the hook is on): `CodeLoupe orientation for <worktree>: branch TER-5-x (task TER-5), default branch main`,
  then what the worktree changed against the merge-base, by declaration (`changes` without the callers, at most `changesLimit` lines).
- **Map** (`"map": true`; off by default, see the measurement in [docs/plan.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/docs/plan.md)): the ranked repository map (`outline` without a target) within
  `budget` tokens (default 1200, 3.2 characters a token as `outline` counts), centred on the files the worktree changed. A resumed or compacted session
  gets the state alone; it has seen the map.

`sessionStart.enabled: false` turns this hook off alone; `hooks.enabled: false` and `CODELOUPE_HOOKS=off` turn off all of them.
`codeloupe metrics orientation --since 2026-10-01` counts `ls`/`find`/`tree`/`Glob` calls in the first 8 turns of sessions, with and without the
hook's context in the transcript, to show whether the map pays for itself. `hooks.jsonl` records each start (kind, tokens, milliseconds; no text).

