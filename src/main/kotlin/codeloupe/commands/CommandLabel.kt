package codeloupe.commands

/** The group a tool call belongs to: [label] names it, [kind] says what sort of call it is (`shell`, `heredoc`, `file`, `search`, `mcp`, `agent`, `other`). */
data class CommandLabel(val label: String, val kind: String)
