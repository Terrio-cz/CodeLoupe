How to give Claude Code the CodeLoupe tools. Two ways to connect, both ending in the same MCP server
(`http://127.0.0.1:47391/mcp`, header `x-codeloupe: 1`; the daemon token reaches it through a headers helper, never in the configuration). `codeloupe` must be on `PATH` (the
[bundle](Packaging-and-releasing#bundle)'s `bin/`) for the session hook; without it the plugin still works while the
daemon runs (tray app, `codeloupe start`).

## 1. Plugin (recommended)

MCP server, a skill saying which tool to use when, and a `SessionStart` hook that runs `codeloupe start`, so the daemon
is up with the session. The repository is its own marketplace:

```bash
claude plugin marketplace add Terrio-cz/CodeLoupe      # or the path of a checkout / the app's claude-plugin folder
claude plugin install codeloupe@codeloupe              # --scope user (default) | project | local
```

The plugin lives in [plugin/](https://github.com/Terrio-cz/CodeLoupe/tree/main/plugin) (`.claude-plugin/plugin.json`,
`.mcp.json`, `hooks/`, `skills/codeloupe/`) and the marketplace manifest in
[.claude-plugin/marketplace.json](https://github.com/Terrio-cz/CodeLoupe/blob/main/.claude-plugin/marketplace.json).
Check changes with `claude plugin validate plugin --strict` and `claude plugin validate .`; try it for one session
without installing: `claude --plugin-dir plugin`. What the hook does and its settings: [Hooks and token
savings](Hooks-and-token-savings#the-sessionstart-hook). The skill,
[SKILL.md](https://github.com/Terrio-cz/CodeLoupe/blob/main/plugin/skills/codeloupe/SKILL.md), is the agent's routing
table (which tool instead of which grep or read); this wiki's [Tools reference](Tools-reference) is for people.

## 2. MCP entry only

No skill, no autostart:

```bash
claude mcp add-json --scope user codeloupe '{"type":"http","url":"http://127.0.0.1:47391/mcp","headers":{"x-codeloupe":"1"},"headersHelper":"codeloupe mcp-headers"}'
```

`codeloupe mcp-config` prints that entry for your port. `headersHelper` runs `codeloupe mcp-headers` on each connection; it prints the
`x-codeloupe` header and, once the daemon has proved it holds `<home>/daemon.token`, the token, so the secret never sits in Claude Code's
configuration. Without the helper (`claude mcp add ... --header "x-codeloupe: 1"`, the entry older versions made) every tool says that it
needs the token, unless `api.strict` is `false` (then only `run`, `env`, `edit` and `job` do); see [Who may call the daemon](Configuration#who-may-call-the-daemon).
The plugin's `.mcp.json` has the helper (`hooks/mcp-headers.sh`), so a plugin install or update needs no extra step.

## Your own agents (subagents with a `tools:` list)

A connected server is not enough for an agent you define yourself. In a controlled run of a reviewer agent that had the tools in its
`tools:` list and one bullet about them in its body, **0.2 CodeLoupe calls per run** came out (1 run of 10); it kept to
`cd <worktree> && rg …`. Adding `grep` to `tools:` alone changed nothing (0 of 4 runs); a short routing table in the body together with
`grep` in `tools:` did (6.0 calls per run, 9 of 10 runs; 2.8 with the table but no `grep`).

- List what you want it to use in `tools:`, `mcp__codeloupe__grep` included: it answers the text, string, SQL and regex searches that make up
  most of an agent's `rg` calls, and is the tool most often forgotten.
- Put the routing table in the body, imperative, and say what `rg` is left for:

  > Code: call `mcp__codeloupe__*` (`root` = the worktree) before `rg`, `sed`, `cat` or a whole `Read`: a symbol → `symbol` / `context`;
  > users → `usages`, callers → `calls`; what the branch changed → `changes`; members of a file → `outline`, always before a `Read` of a `.kt`
  > over 200 lines; a text, string, SQL or regex → `grep`; a name → `find`. `Read` offset/limit only for lines they did not show,
  > `rg -n` only for non-Kotlin files.

  A softer last sentence ("`Read` and `rg -n` for the rest") brought the calls back to 1.5 per run.
- The plugin's steering hook ([Plugin hooks](Plugin-hooks)) is not a substitute: in `advise` mode (the default) it spoke 6-8 times per run
  and the agent did not follow it once; in `redirect` mode the first `rg` over source is refused with the equivalent call, and 4 of 4 runs
  used the tools. The hook belongs to the plugin, so it does not run for an agent in a workspace where the plugin is not installed.

What this buys is adoption, not tokens: the same runs cost the same (median 333 against 323 thousand weighted units, findings unchanged),
because more than half of the tool results of a review run is the review packet the agent reads whole; CodeLoupe only replaces the searches
and range reads around it. Numbers, method and limits: [Benchmarks, Agent runs](Benchmarks#agent-runs).

## From the desktop app

Settings → *Claude Code* shows whether `claude` is found and what is connected, and the
buttons *Connect plugin…* and *Add MCP server only…* run exactly the commands above (after a native confirmation that
lists them, with the daemon's current port), through the `claude` CLI, so Claude Code writes its own configuration.
Without `claude` on `PATH` the card shows the commands to run by hand. The plugin is added from the marketplace folder
next to the app (`resources/claude-plugin` when packaged, `CODELOUPE_PLUGIN_DIR` to override, the repository root in a
development run); the installers ship `.claude-plugin/marketplace.json` and `plugin/` there. The plugin's session hook
looks for `codeloupe` on `PATH`; an installed app does not put it there, so set `CODELOUPE_BIN` to
`<install>/resources/codeloupe/bin/codeloupe` (`.bat` on Windows) or add that directory to `PATH`.

## Another port

A daemon on another port: set `CODELOUPE_PORT` for it and for Claude Code (the plugin's URL reads it); for the MCP entry
the app writes the port it watches, and `codeloupe mcp-config` prints the entry for the configured one.

## If the tools fail

`connection refused` or an MCP server shown as failed means the daemon is not running: run `codeloupe start`, then
reconnect the MCP server with `/mcp`. More in [FAQ and troubleshooting](FAQ-and-troubleshooting).
