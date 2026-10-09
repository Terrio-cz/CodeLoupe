Every tool is available over MCP and as a `codeloupe <tool>` command (`codeloupe <tool> --help` lists its options;
`task_code` also as `code_tasks`). Tools take `root` — the absolute path of the repository or worktree to answer for.
The skill the plugin ships, [SKILL.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/plugin/skills/codeloupe/SKILL.md),
tells an agent which tool to use instead of which grep or read.

## Code tools

| Tool | Returns |
|---|---|
| `find` | declarations by name, `Type.member` or glob: `path:lines [container] signature`; `mode=search` (or a `q` with spaces) ranks declarations for the words of a question (`find q="where is the token limit computed" mode=search`): names split into words, KDoc/Javadoc, signatures and directories, BM25 over SQLite FTS5 with a boost for names and for declarations that are referenced a lot, top 10 by default, each hit followed by the words it matched. Undocumented members of a type are looked up by name, not by words. Worktree edits show in the next search |
| `outline` | members of a file or type with line ranges, no bodies; without a target a map of the repository: files ranked by how much the rest of the code refers to them (PageRank over name references), their types as one-line signatures, cut to `budget` tokens (default 1500); `focus` (files or symbols) puts them first and ranks their neighbourhood, references counted both ways |
| `symbol` | one declaration's source (KDoc, annotations, body) by `Type.member`, `member(ParamType)`, `pkg.Type` or `File.kt:line`; large types collapse to header + members |
| `grep` | text search in the indexed source (Kotlin, `.kts` and Java files, worktree edits included) for string literals, SQL, annotation arguments, config keys: literal by default (`regex=true`, `ignoreCase=true`), hits grouped by file and enclosing declaration, one code line each; `module`, `test`, `limit` narrow it |
| `context` | a declaration's source, its direct callers and the declarations it calls in one answer (`symbol` + `calls` depth 1) instead of three calls |
| `usages` | every reference to a declaration, grouped by file and enclosing declaration, one code line each, `=` exact or `?` candidate; a superset of what `rg -w` finds in code, references that resolve elsewhere only counted (`all=true` lists them) |
| `calls` | callers (default) or callees as a tree, depth ≤ 3; below the first level only exact links |
| `hierarchy` | supertypes and subtypes of a type (object expressions included, and lambdas converted to a `fun interface`), or what a member overrides and what overrides it |
| `job` | start a long command in the daemon (status, cancel); see [Jobs and events](Jobs-and-events); takes `cwd`, not `root` |
| `run` | a command that ends soon (`command` = argv, `cwd`; `timeoutSec`, default 120) answered with a summary instead of its output, as a job (same policy hook, log and scrubbing as `job`). Header: `<command> · exit 0 · 8700 → 380 chars (96% less) · full output: doc path=job:<id>`. git status = branch and a count with names per kind; git log = one line per commit (a shared author or day said once); `git diff --stat` = totals and the 12 biggest files, a patch = one line per file with `+added -removed`; Gradle = the `BUILD` line, task counts, failed tasks, every failed test with its exception and first-party frames, every `e:` compiler error (working directory cut off), a few warnings, `What went wrong`; node:test, Jest, pytest, Maven = counts, failures and last lines; anything else = first 5 lines, every error line (more than 20 are counted) and the last 10, repeated lines folded. After a failed build or test run (exit not 0) the errors come with the declaration they are in (the index of the worktree): kotlinc, Gradle-Kotlin and javac errors are grouped per declaration as `path:lines  [Container] fun name(…)  · symbol Container.name hash=…` (the call and hash for `symbol`/`edit`) with `line:col  message` under it, a message repeated in three or more declarations is said once ("same error ×N in M declarations"), a failed test shows `expected <a>, was <b>` and its frames with the declaration of the first production frame; lines the index cannot place stay as they were, and the summary never grows by more than 15 %. An output under 600 characters and 20 lines is returned as it is, `raw=true` returns everything. A command still running after the timeout answers with its job id. The recorded outputs behind the claims are in `src/test/resources/outputs` (about 88 % less over the set; git status, git log and a Gradle failure each at least 60 %) |
| `changes` | what the worktree changed against the merge-base with the default branch (committed and uncommitted), by declaration: `+` added, `~` body changed, `^` signature changed (with the old one), `-` removed; each with its callers and tests; `bodies=true` adds a line diff per declaration; `tests=true` prints instead the Gradle command that runs the tests that use them (`./gradlew :module:test --tests 'pkg.Class'`, class level), widening to the module for a declaration no test reaches or a changed resource and to the full suite for a build file |
| `task_code` | links between tasks and code, from the default branch's history (works without a tracker). `query` = a task id (`ABC-5`): its landing commit, files and changed declarations (`+ ~ ^ -`), the worktree whose branch names it, and for an open task the touch set predicted from its text — `=` sure · `~` likely · `?` guess · `+` new file, each with the issue text it comes from. `query` = a declaration (`Type.member`) or a file path: the tasks that changed it, newest first, with landing commits (`code_tasks <symbol\|path>` on the command line), plus open tasks whose text points at it. Task ids follow the tracker projects, or `taskPattern` (a regular expression) in `.codeloupe.json` |
| `doc` | a text file (a plan, a note, a large persisted tool output, a job's full log) as a digest, a section or a line window, and on a repeated read as what changed; works without a tracker or a repository; see [Hooks and token savings](Hooks-and-token-savings#documents-doc) |
| `env` | the names, scopes and last use of the stored variables and secrets of a workspace, never a value; see [Environment and secrets](Environment-and-secrets) |
| `edit` | change source by declaration instead of by text (replace, insert, delete, add imports, create a file, rename), verified before it is written; offered only where the write policy allows it, see [below](#editing-by-declaration-edit) |

## Tracker tools

With a tracker configured ([Trackers](Trackers)) more tools work on a local mirror of its projects:

| Tool | Returns |
|---|---|
| `issue` | one issue as compact markdown: `view=brief` (fields, links, criteria checklist, section index), `full`, or `sections=[…]` (description headings by prefix, `criteria`, `fields`, `links`, `comments`, `attachments`, `history`). A second read from the same `root` answers `unchanged since …` or only what changed; `since=<ISO time>` diffs against that time, `since=none` shows it again |
| `similar` | before creating an issue: the open (or last 90 days resolved) tasks that talk about the same thing as a draft `summary` / `description`, best full-text match first (bm25, summary weighs most), each with the words it shares; extend or link one instead of creating a duplicate. From the mirror, in well under 100 ms |
| `task_context` | a planner's starting pack for a task in one call instead of `issue` + `tasks` + `task_code` + search: sections `issue` (brief with criteria), `linked` (dependencies and relations with state), `open-criteria` (what related open tasks still owe), `touch` (landed, in a worktree, or predicted code), `declarations` (outline lines of those files, no bodies), `prior` (earlier tasks that changed the same files, each with its landing commit). `sections=[…]` picks some, `view=digest` lists them with sizes. The same `root` asking again gets `unchanged since …` in one line, or only the sections that changed; `since=none` sends everything again |
| `dispatch_plan` | which ready tasks get a window now so that no two windows touch the same code. Candidates: `candidates=[ids]`, `epic=<id>` (its open leaf tasks), `query=<filters>`, or none (the 15 most urgent open leaf tasks). Each task's touch set is its landed files, else what its text names (as in `task_code`); sets are compared with each other and with every live worktree (the files it changed plus what its task is predicted to touch). Answer: `windows` — a batch of up to 3 light tasks, a chain of up to 3 steps (dependencies first), or one task, each with the shared code that put its tasks together and per-task keys (`file:`, `dir:`); `waiting` — fights with a live worktree (file and worktree cited), unmet dependency, no free slot (`slots`, default 4), rest of a long chain; `live`. A task whose text names no code is listed as unproven. `width=dir` (default) counts one directory as a clash, `file` only one file; markdown never blocks. Same `sections` / `view` / `since` as `task_context`. The caller keeps the final judgment |
| `tasks` | one line per task (`id state · type · priority ‹epic› title ⛔blockers`). `mode=list` with a YouTrack-like `query` (`project: ABC state: -Done #unresolved epic: ABC-1 type: Bug {Fix versions}: 1.0 sort: id` plus full-text words), `graph` (an issue's epic, dependencies, subtasks, relations; `depth` ≤ 3), `ready` (open tasks without open subtasks whose dependencies are resolved and that no git worktree branch holds), `progress` (an epic: counts by state, criteria, blockers, open tasks) |
| `update` | writes to the tracker: `set={Field: value}` (State, Assignee, Priority, Type, `summary`, `description` or any custom field; comma-separated for multi-value fields; an empty value clears) and/or `comment=<text>`. Answers one line of at most 300 characters — the fields that changed (`State: To do→Done`), `+comment <id>`, and the state when it did not change — instead of the issue. The mirror stores the tracker's own answer to the write, so the next `issue` read needs no request |

## Editing by declaration (`edit`)

`edit` changes source by declaration instead of by text, one tool for every write (the tool count is capped, so it is one tool with an
`op`): `replace` (the whole declaration, its KDoc and annotations, becomes `code`), `insert_after` / `insert_before` (beside the anchor), `insert_member`
(into a type: `position` `start`, `end` or `after_properties`), `delete`, `add_imports` (`file`, `imports`: sorted into their group, none twice),
`create_file` (`path`, `code`: new files only; the package must be the folder's, a public Java type the file's name) and `rename` (`name`, `to`; `dry_run`
plans only). The name is the one `symbol` takes and the `hash` is the `hash=` it printed: a declaration changed since is refused. Behind
every write: the declaration is looked up again in the file as it is on disk; the code is re-indented and gets the file's line ends (a byte order mark, mixed
line ends, a missing final newline and tabs are kept - writing a declaration back as it was changes no byte); the result is parsed again and nothing is
written unless the file has no more syntax errors than before, declares exactly what it declared outside the edited range, and declares what the code
declares; the file is replaced atomically (retried when an editor holds it); every write goes to `<home>/writes.jsonl` (operation, files, SHA-1
before and after); the next query sees the change at once.

`rename` carries the declaration, what it overrides and what overrides it, a class with its constructors, every usage the index is sure of (`exact`),
the imports of it, and the file of a public Java type; what the index only suspects (`candidate`) is listed for you, not changed (unless it can only be a use of the renamed declarations: nothing else of that name is declared in the index and no library is known to declare it). It refuses a name that exists
already or would be captured by a local, a member that overrides something outside the index, operators and conventions read by name
(`toString`, `compareTo`, `main`, ...). Before writing, the renamed worktree is indexed beside the rest and every place that is renamed must resolve to the renamed
declaration: that check stands in for a compiler. Strings, comments and generated code are not changed.

The write policy: only `.kt` and `.java` files inside the worktree, never `.git`, `.codeloupe.json`, secrets and keys (`.env`, `*.pem`, `*.key`...), or a file with merge-conflict markers.
`config.json` `write` adds `linkedWorktreesOnly` (no writes in the main checkout) and `deny` globs; a repository's own `.codeloupe.json` `write` can add the
same two and cannot enable anything. `write.mode` is `off`, `on` or `auto` (the default): `auto` offers `edit` only when the transcripts of the last 30 days show the gaps it closes - at
least 20 reads of a whole code file followed by an edit of it, or 3 runs that renamed one identifier by hand in three files or more (`codeloupe metrics gaps` prints the verdict; thresholds under `write.gate`).
Without the tool on offer the catalog stays at 14 tools; with it, 15. Whether `edit` is on offer is decided when the daemon starts and stays so while it runs: a client caches the tool list in its prompt prefix, so a list that changed under a session would cost that session its cache. A verdict of the `auto` gate that arrives later (it is worked out in the background) takes effect at the next start.

`/status` `toolList` holds a `fingerprint` of the list (version, and every tool's name, description and schema), the number of tools and whether `edit` is offered: equal fingerprints mean byte-equal lists, whatever the daemon was asked or how often it restarted.

## Commands that are not tools

The CLI has a few more commands; they talk to the daemon but are not offered to agents as tools.

| Command | Does | Page |
|---|---|---|
| `start`, `stop`, `status`, `daemon` | start the background daemon, stop it (the desktop app then leaves it stopped), print its status as JSON, run it in the foreground | [Getting started](Getting-started), [Daemon and index](Daemon-and-index) |
| `repos add`, `repos list` | add repositories to the configuration and have them indexed, list them | [Configuration](Configuration#repositories) |
| `workspaces`, `ws ...` | worktrees with their state, labelled Docker resources, processes, ports, reconcile, release | [Workspaces and Docker cleanup](Workspaces-and-Docker-cleanup) |
| `webhook add\|list\|remove\|deliveries` | subscriptions to the daemon's events | [Jobs and events](Jobs-and-events#events) |
| `env set\|unset\|run\|import\|audit` | write to the secret store, run a command with secrets | [Environment and secrets](Environment-and-secrets) |
| `metrics collect\|compare\|gaps\|boilerplate` | measure agent runs from Claude Code transcripts | [Metrics and savings](Metrics-and-savings) |
| `mcp-config` | print the `.mcp.json` entry for Claude Code | [Claude Code integration](Claude-Code-integration) |
| `mcp-headers` | print the headers an MCP client sends (the daemon token among them), for the `headersHelper` of that entry | [Who may call the daemon](Configuration#who-may-call-the-daemon) |
