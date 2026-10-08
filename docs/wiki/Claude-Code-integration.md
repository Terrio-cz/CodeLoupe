How to give Claude Code the CodeLoupe tools. Two ways to connect, both ending in the same MCP server
(`http://127.0.0.1:47391/mcp`, header `x-codeloupe: 1`, no secret). `codeloupe` must be on `PATH` (the
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
claude mcp add --transport http --scope user codeloupe http://127.0.0.1:47391/mcp --header "x-codeloupe: 1"
```

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
