<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/banner-dark.svg">
    <img alt="CodeLoupe: read less, know more" src="docs/brand/banner-light.svg" width="830">
  </picture>
</p>

# CodeLoupe

On-demand code index for AI coding agents. Ask for a declaration, a file outline, its usages, callers or type
hierarchy, or what a branch changed, and get exactly that piece of code instead of grepping and reading whole files.
With a tracker configured it also mirrors your issues (YouTrack first) and answers issue reads and task queries locally.

An agent working on a Kotlin repository keeps asking where something is declared, what a file contains, who uses or calls
it and what its branch changed. Without an index it answers with `rg` and whole-file reads; CodeLoupe answers each question
with one call. Over two public repositories its answers are 3-41 % of the size of grep plus reading (8 % summed over all
questions), measured, not estimated ([Benchmarks](https://github.com/Terrio-cz/CodeLoupe/wiki/Benchmarks); controlled runs of real agents: [Agent runs](https://github.com/Terrio-cz/CodeLoupe/wiki/Benchmarks#agent-runs)):

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/benchmarks-dark.svg">
  <img alt="Median tokens read per question: CodeLoupe, GitNexus, grep + read, grep minimal" src="docs/benchmarks.svg" width="830">
</picture>

- **Any git repository**, no configuration. Kotlin and Java sources; the base index follows the default branch and is built
  from git objects, every worktree adds an overlay of its own edits, checked when a query arrives.
- **No IDE, no compiler, no watchers**: the Kotlin compiler's own parser (syntax only) and SQLite; no CPU while idle.
- **One daemon per machine** for every agent window, about 210 MB resident with two repositories indexed.
- **MCP** over Streamable HTTP (stateless) plus the same tools on a CLI. The daemon is local-only and everything that acts for you (jobs, `run`, `edit`, releases) wants the token in its home's `daemon.token`, which only you can read; [Who may call the daemon](https://github.com/Terrio-cz/CodeLoupe/wiki/Configuration#who-may-call-the-daemon).

## Quick start

CLI (git 2.31 or newer; JDK 25 only to build from source, a release bundle carries its own runtime):

```bash
./gradlew installDist                                   # put build/install/codeloupe/bin on PATH
codeloupe outline OrderService                          # from inside a repository; the first query builds its index
codeloupe symbol "OrderService.handle(_)"
codeloupe usages OrderService.handle
codeloupe changes                                       # what this branch changed, by declaration
```

Claude Code (MCP server, a skill saying which tool to use when, and a hook that starts the daemon with the session):

```bash
claude plugin marketplace add Terrio-cz/CodeLoupe
claude plugin install codeloupe@codeloupe
```

## What it does

- **Navigate and review**: `find`, `outline`, `symbol`, `context`, `usages`, `calls`, `hierarchy`, `grep`, and `changes` (the
  declarations a branch changed; `callers=true` adds callers and tests). Unsure references are marked `candidate`, never dropped.
- **Steer**: the plugin's hook points shell searches and whole-file reads of indexed source at the call that answers them.
- **Edit by declaration**: `edit` replaces, inserts, deletes and renames declarations, verified before anything is written.
- **Run long commands**: `job` runs builds and tests in the daemon so an agent's turn can end; `run` answers a short command
  with a summary and a handle to the rest.
- **Tasks**: a mirror of your tracker answers `issue`, `tasks`, `task_context` and `dispatch_plan` locally; `task_code` joins
  tasks to the code that landed for them.
- **Workspaces and secrets**: worktrees with their task and state, labelled Docker resources, build daemons and ports, cleaned
  up when a task is released; one encrypted store for variables, so an agent never sees a value.
- **Measure and watch**: `codeloupe metrics` shows from Claude Code transcripts where context and cost go; a desktop app shows
  cost, workspaces, jobs and runs.

## Tools

Every tool is on MCP and on the CLI (`codeloupe <tool> --help`); details in the
[Tools reference](https://github.com/Terrio-cz/CodeLoupe/wiki/Tools-reference).

| Tool | Returns |
|---|---|
| `find` | declarations by name, `Type.member` or glob; `mode=search` ranks them for the words of a question |
| `outline` | members of a file or type with line ranges, or a ranked map of the repository |
| `symbol` | one declaration's source: KDoc, annotations, body |
| `context` | a declaration's source with its callers and callees in one answer |
| `usages` | every reference to a declaration, `=` exact or `?` candidate |
| `calls` | callers or callees as a tree |
| `hierarchy` | direct subtypes of a type (`supers=true`: its supertypes too, `deep=true`: transitively), overrides of a member |
| `grep` | text search in indexed source, hits grouped by enclosing declaration |
| `changes` | what a worktree changed against the merge-base, by declaration |
| `edit` | change source by declaration: replace, insert, delete, add imports, create a file, rename |
| `run` | a short command answered with a summary instead of its output |
| `job` | start, check and cancel a long command in the daemon |
| `doc` | a text file by digest, section or line window; a repeated read answers what changed |
| `env` | names, scopes and last use of stored variables, never a value |
| `task_code` | the code a task touched, or the tasks that touched a declaration |
| `issue` | one tracker issue as compact markdown (needs a tracker) |
| `tasks` | list, graph, ready tasks or epic progress (needs a tracker) |
| `task_context` | a planner's whole starting pack for a task: issue, description, comments, linked tasks, touched code with its callers, rules that apply (needs a tracker) |
| `dispatch_plan` | which ready tasks can run in parallel without touching the same code, with each task's state and epic (needs a tracker) |
| `similar` | open tasks that look like a draft issue (needs a tracker) |
| `update` | set fields and add a comment on a tracker issue (needs a tracker) |

## Documentation

The manual is the [wiki](https://github.com/Terrio-cz/CodeLoupe/wiki); its source is [`docs/wiki`](docs/wiki).

| | Pages |
|---|---|
| Use | [Getting started](https://github.com/Terrio-cz/CodeLoupe/wiki/Getting-started), [Claude Code integration](https://github.com/Terrio-cz/CodeLoupe/wiki/Claude-Code-integration), [Tools reference](https://github.com/Terrio-cz/CodeLoupe/wiki/Tools-reference), [Configuration](https://github.com/Terrio-cz/CodeLoupe/wiki/Configuration), [FAQ and troubleshooting](https://github.com/Terrio-cz/CodeLoupe/wiki/FAQ-and-troubleshooting) |
| Features | [Jobs and events](https://github.com/Terrio-cz/CodeLoupe/wiki/Jobs-and-events), [Workspaces and Docker cleanup](https://github.com/Terrio-cz/CodeLoupe/wiki/Workspaces-and-Docker-cleanup), [Trackers](https://github.com/Terrio-cz/CodeLoupe/wiki/Trackers), [Environment and secrets](https://github.com/Terrio-cz/CodeLoupe/wiki/Environment-and-secrets), [Hooks and token savings](https://github.com/Terrio-cz/CodeLoupe/wiki/Hooks-and-token-savings), [Metrics and savings](https://github.com/Terrio-cz/CodeLoupe/wiki/Metrics-and-savings), [Desktop app](https://github.com/Terrio-cz/CodeLoupe/wiki/Desktop-app) |
| How it works | [Daemon and index](https://github.com/Terrio-cz/CodeLoupe/wiki/Daemon-and-index), [Worktrees and overlays](https://github.com/Terrio-cz/CodeLoupe/wiki/Worktrees-and-overlays), [Benchmarks](https://github.com/Terrio-cz/CodeLoupe/wiki/Benchmarks) ([agent runs](https://github.com/Terrio-cz/CodeLoupe/wiki/Benchmarks#agent-runs)), [Comparison with GitNexus and IDE servers](https://github.com/Terrio-cz/CodeLoupe/wiki/Comparison) |
| Install and contribute | [Installers and updates](https://github.com/Terrio-cz/CodeLoupe/wiki/Installers-and-updates), [Packaging and releasing](https://github.com/Terrio-cz/CodeLoupe/wiki/Packaging-and-releasing), [Development](https://github.com/Terrio-cz/CodeLoupe/wiki/Development) |

In the repository: [docs/plan.md](docs/plan.md) (plan, decisions, measurements; Czech), [docs/ui-spec.md](docs/ui-spec.md)
(desktop app specification; Czech), [docs/benchmarks.md](docs/benchmarks.md) (generated report), [app/README.md](app/README.md),
[plugin/](plugin).

## Install

- **Desktop app and CLI**: an installer per OS from the [Releases](https://github.com/Terrio-cz/CodeLoupe/releases) page;
  no release is published yet, and every CI run keeps its installers for 7 days as artifacts. The installers are unsigned
  ([how to install without a warning](https://github.com/Terrio-cz/CodeLoupe/wiki/Installers-and-updates)).
- **Daemon and CLI only**: the `codeloupe-<version>-<os>-<arch>.zip` bundle (it carries its own Java runtime).
- **From source**: `./gradlew installDist` with JDK 25.

## Contributing and status

Issues and pull requests are welcome; read [CONTRIBUTING.md](CONTRIBUTING.md) (build, test, checks, licence of
contributions) and the [Code of Conduct](CODE_OF_CONDUCT.md), and see
[Development](https://github.com/Terrio-cz/CodeLoupe/wiki/Development) for the layout. Report a vulnerability privately,
as [SECURITY.md](SECURITY.md) describes. The wiki is edited as files in [`docs/wiki`](docs/wiki).
Early: version 0.1.0, no public release yet. Roadmap and results per step: [docs/plan.md](docs/plan.md). Limits that matter
(Kotlin and Java only, syntax-level resolution, no semantic search) are in
[FAQ and troubleshooting](https://github.com/Terrio-cz/CodeLoupe/wiki/FAQ-and-troubleshooting).

## Licence

CodeLoupe is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE): you may use, study, modify
and share it for any noncommercial purpose (personal use, research, education, charities, public institutions), but
not sell it, offer it as a paid product or service, or use it for commercial purposes. This is not an open-source
licence in the OSI sense. For commercial use, contact the licensor (Terrio-cz).
