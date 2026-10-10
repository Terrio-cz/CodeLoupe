**CodeLoupe** is an on-demand code index for AI coding agents. Ask for a declaration, a file outline, its usages, callers or
type hierarchy, or what a branch changed, and get exactly that piece of code instead of grepping and reading whole files.
It works on any git repository (Kotlin and Java), needs no IDE and no configuration, and serves every agent window of a
machine from one small daemon over MCP and a CLI.

New here? Start with [Getting started](Getting-started), then [Claude Code integration](Claude-Code-integration). The
short version, a landing page and the download, is the
[README](https://github.com/Terrio-cz/CodeLoupe#readme).

## Use it

| Page | What is in it |
|---|---|
| [Getting started](Getting-started) | requirements, install, the first queries |
| [Claude Code integration](Claude-Code-integration) | the plugin, the MCP entry, the desktop app's buttons |
| [Tools reference](Tools-reference) | every tool and what it returns, editing by declaration |
| [Configuration](Configuration) | home, port, `config.json`, per-repository `.codeloupe.json` |
| [FAQ and troubleshooting](FAQ-and-troubleshooting) | limitations, short answers, what to try when something fails |

## Features

| Page | What is in it |
|---|---|
| [Jobs and events](Jobs-and-events) | long commands in the daemon, slots, policy, events and webhooks |
| [Workspaces and Docker cleanup](Workspaces-and-Docker-cleanup) | worktrees with their state, labelled Docker resources, the reconciler, build daemons, ports |
| [Trackers](Trackers) | the YouTrack mirror and the tools on top of it |
| [Environment and secrets](Environment-and-secrets) | the encrypted store, `env run`, import, audit |
| [Hooks and token savings](Hooks-and-token-savings) | the session hook, `doc` and the layers that keep the context small |
| [Plugin hooks](Plugin-hooks) | the `PreToolUse` hook that points searches and whole-file reads at CodeLoupe, its modes and measurements |
| [Metrics and savings](Metrics-and-savings) | where an agent's cost goes, measured from transcripts |
| [Desktop app](Desktop-app) | the tray app and its screens, accounts |

## How it works

| Page | What is in it |
|---|---|
| [Daemon and index](Daemon-and-index) | one daemon per machine, what the index holds, memory and latency |
| [Worktrees and overlays](Worktrees-and-overlays) | one base index, an overlay per worktree, no watchers |
| [Benchmarks](Benchmarks) | answer size against grep and whole-file reads, cost of having the tool, controlled runs of real agents |
| [Comparison](Comparison) | against GitNexus and an IDE's MCP server |

## Install and contribute

| Page | What is in it |
|---|---|
| [Installers and updates](Installers-and-updates) | per-OS installers, installing without a warning, how updates work |
| [Packaging and releasing](Packaging-and-releasing) | the bundle, the installers, cutting a release, the update feed |
| [Development](Development) | build, test, measure, the packages, the documents and this wiki |

CodeLoupe is source-available under the
[PolyForm Noncommercial License 1.0.0](https://github.com/Terrio-cz/CodeLoupe/blob/main/LICENSE).
