From nothing to the first answer: install CodeLoupe, ask a question in a repository, and know where its state lives.
It works on any git repository with Kotlin or Java sources, with no configuration.

## Requirements

git ≥ 2.31 and either the bundle (it carries its own Java runtime) or, to build from source, JDK 25
(Gradle finds or downloads it as a toolchain).

## Install

| You want | Do |
|---|---|
| The desktop app, tray, daemon and CLI in one download | An installer from the [Releases](https://github.com/Terrio-cz/CodeLoupe/releases) page (no release is published yet; every CI run keeps its installers for 7 days as artifacts); see [Installers and updates](Installers-and-updates) |
| Only the daemon and the CLI, no Java needed | The `codeloupe-<version>-<os>-<arch>.zip` bundle of a release (or of a CI run); unpack it and put its `bin/` on `PATH` ([Packaging and releasing](Packaging-and-releasing#bundle)) |
| To build from source | `./gradlew installDist`, then `build/install/codeloupe/bin/codeloupe` (needs JDK 25) |

## First queries

```bash
./gradlew installDist
build/install/codeloupe/bin/codeloupe outline OrderService          # from inside a repository
build/install/codeloupe/bin/codeloupe symbol "OrderService.handle(_)"
build/install/codeloupe/bin/codeloupe find "*Repository" --kind interface
build/install/codeloupe/bin/codeloupe usages OrderService.handle
build/install/codeloupe/bin/codeloupe calls OrderService.handle --depth 2      # --callees for what it calls
build/install/codeloupe/bin/codeloupe hierarchy Repository
build/install/codeloupe/bin/codeloupe changes                    # what this branch changed, by declaration
build/install/codeloupe/bin/codeloupe issue ABC-5 --section scope   # with a tracker configured
build/install/codeloupe/bin/codeloupe tasks "epic: ABC-1" --mode ready
build/install/codeloupe/bin/codeloupe task_code ABC-5            # its landed or predicted code
build/install/codeloupe/bin/codeloupe code_tasks OrderService.handle   # the tasks that touched it
build/install/codeloupe/bin/codeloupe task_context ABC-5         # a planner's whole starting pack; asked again: what changed
build/install/codeloupe/bin/codeloupe doc plan.md --section goal # a text file by digest, section or line window
build/install/codeloupe/bin/codeloupe run -- git log -30         # a short command: its summary, the rest by handle (doc path=job:<id>)
build/install/codeloupe/bin/codeloupe env list                   # secret names, scopes and last use; never a value
build/install/codeloupe/bin/codeloupe env run --repo repo -- docker compose up   # a command with the secrets in its environment
build/install/codeloupe/bin/codeloupe status
```

Every command takes `--root` (default: the current directory), and `codeloupe <command> --help` lists its options. What
each tool returns is in the [Tools reference](Tools-reference).

The first query in a repository builds its index (seconds); later queries take milliseconds. The
daemon starts on the first CLI call; `codeloupe start` / `stop` manage it explicitly. `codeloupe status` prints the
daemon's state as JSON (exit code 3 when it is not running); `codeloupe repos add <folder>...` makes it know a repository
before the first question ([Configuration](Configuration#repositories)).

A CLI call against a running daemon takes ~0.2 s. The start script keeps a JVM class-data archive in
`<home>/cds/` (about 8 MB per install and build); the first call after an install creates it (~1.5 s), and it is
simply not used when that directory is not writable. `JAVA_OPTS` / `CODELOUPE_OPTS` add JVM flags.

## Where things live

State, indexes, logs and secrets live in the daemon's home (`CODELOUPE_HOME`; default `%LOCALAPPDATA%\codeloupe`,
`~/Library/Caches/codeloupe` or `$XDG_CACHE_HOME/codeloupe`). The daemon listens on `127.0.0.1:47391` only. Nothing is
written into the repositories it indexes. Both are covered in [Configuration](Configuration).

## Next

- Connect it to Claude Code: [Claude Code integration](Claude-Code-integration).
- Learn how it answers and what it keeps in memory: [Daemon and index](Daemon-and-index).
- Something does not work: [FAQ and troubleshooting](FAQ-and-troubleshooting).
