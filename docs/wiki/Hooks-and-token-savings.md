What keeps an agent's context small: the hook that has the daemon ready when a session starts, and the layers
that answer with less than a plain read would.

## The SessionStart hook

The [Claude Code plugin](Claude-Code-integration#1-plugin-recommended) registers two hooks. `SessionStart`
(`startup|resume|clear|compact`) runs [`start-daemon.sh`](https://github.com/Terrio-cz/CodeLoupe/blob/main/plugin/hooks/start-daemon.sh)
and so `codeloupe start`: the daemon is up before the first tool call. It then asks the daemon for the state of the worktree (branch,
task, what it changed) and, if switched on, the repository map: [Plugin hooks](Plugin-hooks#session-start). It never fails a session
and prints nothing else unless asked, because the output of a SessionStart hook reaches the model.

| Setting | Effect |
|---|---|
| `CODELOUPE_BIN` | the launcher to run when `codeloupe` is not on `PATH` (an installed desktop app does not put it there: `<install>/resources/codeloupe/bin/codeloupe`, `.bat` on Windows) |
| `CODELOUPE_HOOK_VERBOSE=1` | print why the hook did nothing (not installed, did not start) or the output of `codeloupe start` |

The hook needs `bash` (Git Bash on Windows, which Claude Code uses anyway).

The second hook, `PreToolUse` on `Bash`, `PowerShell` and `Read`, points shell searches (`rg`, `grep`, `find -name`) and
reads of whole indexed source files at the CodeLoupe call that answers them. It fails open, advises by default and has one
switch to turn every hook off: [Plugin hooks](Plugin-hooks).

## Where the tokens go

| Layer | What it saves | Details |
|---|---|---|
| Steering hook | a shell search or a whole-file read of indexed source is answered by a pointer to `find`, `usages`, `grep` or `outline` | [Plugin hooks](Plugin-hooks) |
| Declaration-level answers | `symbol`, `outline`, `usages`, `calls`, `hierarchy` and `changes` return the piece asked for instead of files: 3-41 % of the size of grep and whole-file reads, 8 % summed | [Benchmarks](Benchmarks) |
| `run` | a short command answered with a summary (every error line kept) and a handle to the full output: about 88 % less over the recorded set | [Tools reference](Tools-reference) |
| `job` | a long command runs in the daemon, so no turn is held open on a build | [Jobs and events](Jobs-and-events) |
| `doc`, `issue`, `task_context` | a repeated read from the same `root` answers in one line, or only what changed | below |
| `outline` without a target | a ranked map of the repository cut to a token budget (default 1500) | [Tools reference](Tools-reference) |
| `update` | one line (at most 300 characters) instead of the issue | [Trackers](Trackers) |
| `codeloupe metrics` | shows where a context still goes, per tool category, from Claude Code transcripts | [Metrics and savings](Metrics-and-savings) |

## Documents (`doc`)

One layer serves every large text an agent would otherwise read twice: `doc` (plans, brain notes, persisted tool outputs)
and `task_context` (the planner's pack) both split their text into **sections with handles** (markdown headings and
`=== title ===` banners; an output without headings is cut into line windows `L1-60`, `L61-120`, …), hash each section and remember,
per caller (`root`), what that caller was shown.

| Call | Answer |
|---|---|
| `doc path` | a digest of at most 1000 characters: size, hash, the sections as `handle(lines)`, the lines that look like errors as `L118-124 "FAILED: …"`, and how to fetch |
| `doc path --section goal --section L118-124` | those sections (handle or heading prefix, with their sub-sections) or line windows (at most 300 lines, 20 000 characters) |
| `doc path --view outline` / `--view full` | every section with its line; the whole text |
| `doc job:<id>` | the full output a `run` or `job` kept, scrubbed of secrets, by the same digest (error windows `L118-124 "FAILED: …"` included), sections and line windows; the newest 8 MB of a larger log |
| the same call again | one line, `plan.md unchanged since your read at … (#hash, 9 sections)`, under 100 tokens |
| after the file changed | the digest becomes a delta (`~ steps(12)`, `+ notes(3)`, `- old`); `--view full` sends only the changed sections in full and names the omitted ones; a fetched section that did not change answers `section steps unchanged` |
| `--since none` | forget what the caller has; read it again |

`doc` reads only files under the caller's `root` or under the agent harness's folders (`~/.claude`, `<tmp>/claude`), with links
resolved first, and never a secret store (`.env*`, `*.pem`, `*.key`, `id_rsa*`, `credentials*`, `.ssh`, `.aws`, …), a binary file or one over 8 MB.
The memory is per daemon and per `root`; it is not saved over a daemon restart (the next read is a full one), and a session or subagent starting in the worktree (the plugin's `SessionStart` and `SubagentStart` hooks) clears it for that worktree, so a new agent is never told `unchanged` of text it has not read.
