Short answers, the known limits, and what to try when something does not work.

## Questions

**Does it need an IDE or a compiler?** No. Declarations and references come from the Kotlin compiler's own parser
(syntax only, no classpath) and a SQLite index built from git objects.

**Which repositories and languages?** Any git repository, no configuration. Kotlin and Java are indexed.

**Does indexing change my checkout?** No. The index lives in the daemon's home; nothing is written into the repository
(measured, see [Benchmarks](Benchmarks)). The only tool that writes source is `edit`, and only where the write policy
allows it ([Tools reference](Tools-reference#editing-by-declaration-edit)).

**What does it cost while idle?** No CPU: there are no file watchers and no timers; a worktree is checked when a query
arrives. The daemon is about 200 MB resident ([Daemon and index](Daemon-and-index)).

**Can several agent windows share it?** Yes: one daemon per machine, started on demand, serves every window, and every
worktree of a repository shares one base index ([Worktrees and overlays](Worktrees-and-overlays)).

**Can I use it commercially?** The licence is [PolyForm Noncommercial
1.0.0](https://github.com/Terrio-cz/CodeLoupe/blob/main/LICENSE): personal, research, educational and other noncommercial
use; commercial use needs an agreement with the licensor.

## Limitations

- **Languages**: Kotlin (`.kt`, and `.kts` for text search) and Java (`.java`). Other files are not indexed. In a mixed repository a Java
  file's references to Kotlin top-level functions (`GreeterKt.polite(…)`, the file facade) and Kotlin's synthetic property access to
  a Java getter (`x.name` for `getName()`) are not followed.
- **Syntax-level resolution**: no classpath, no compiler. Overloads are told apart by argument count, receivers by the
  types syntax shows, so some references stay `candidate` and a name shared by unrelated declarations must be
  qualified (`Type.member`). `usages` counts resolved references, not every line that holds the word.
- **Search**: no semantic search and no execution-flow or impact analysis. `find mode=search` ranks declarations for the
  words of a question (names, KDoc, signatures, paths: lexical, not meaning); `grep`, `usages`, `calls`, `hierarchy` work on names.
- **Runtime**: git ≥ 2.31, and the bundle or JDK 25. One daemon of about 200 MB; a repository's first query builds its
  index (seconds for the repositories measured, longer for larger ones; the largest measured has 802 Kotlin files including tests).
- **Licence**: source-available under [PolyForm Noncommercial 1.0.0](https://github.com/Terrio-cz/CodeLoupe/blob/main/LICENSE), not open source in the OSI sense. It
  allows personal, research, educational and other noncommercial use and does not allow commercial use without
  agreement with the licensor. GitNexus is under the same licence; check your own situation before choosing either.
- **Evidence**: the benchmark measures answer size, latency and resources, not agent task success; it covers two
  repositories and one machine. Treat the percentages as a measurement of these cases, not a general guarantee.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `connection refused`, or the MCP server shows as failed in Claude Code | The daemon is not running. `codeloupe start`, then reconnect with `/mcp`. `codeloupe status` prints its state (exit code 3 when it is not running). |
| The session hook did nothing | `codeloupe` is not on `PATH`: set `CODELOUPE_BIN` to its launcher, and `CODELOUPE_HOOK_VERBOSE=1` to see why ([Hooks and token savings](Hooks-and-token-savings#the-sessionstart-hook)). |
| The daemon will not start on 47391 | A daemon with another `CODELOUPE_HOME` refuses 47391 and the port configured in the default home: give it a port of its own (`CODELOUPE_PORT`), and set the same variable for Claude Code ([Configuration](Configuration)). |
| The desktop app does not start the daemon | After `codeloupe stop` (or a stop in the app) the app leaves it stopped until the next `codeloupe start`. |
| `codeloupe stop` refuses | Jobs are running; `--force` ends them. |
| The first query is slow | It builds the repository's index (seconds; longer for large repositories), and the first call after an install creates the class-data archive (~1.5 s). Later queries take milliseconds. |
| An answer says `busy` | The daemon had no result within the query wait (10 s), for instance while an index was building; ask again. `queryTimeoutMs` and `maxParallelQueries` are in [Configuration](Configuration). |
| `usages` lists hits marked `?` | They are candidates: the syntax cannot tell which declaration they mean. Qualify the name (`Type.member`) to narrow it. |
| A job was refused (exit 2) | The `policyHook` denied it, crashed, timed out or printed unreadable output; the command never ran ([Jobs and events](Jobs-and-events)). |
| A job shows `lost` | The daemon restarted while it was queued or running; its log is kept ([Jobs and events](Jobs-and-events)). |
| A Windows `.bat` job refuses its arguments | A batch file takes no argument holding `& \| < > ^ % ! " ( )`, which cmd.exe would parse again; pass such a value through `--env K=V` instead. |
| A worktree directory cannot be deleted on Windows | An idle Gradle daemon still holds it; the reconciler stops build tools of released workspaces ([Workspaces and Docker cleanup](Workspaces-and-Docker-cleanup#processes-and-build-daemons-per-workspace)). |
| Windows SmartScreen or macOS Gatekeeper warns about the installer | The installers are unsigned; see [Installers and updates](Installers-and-updates#installing-without-a-warning). |
| After an update the index is rebuilt | An index written in an older format is rebuilt, never served. |

Logs: `<home>/daemon.log` (the daemon) and `<home>/calls.jsonl` (tool, latency and size of every call, no content);
`codeloupe status` and `/status` show latency and the budget warnings.
