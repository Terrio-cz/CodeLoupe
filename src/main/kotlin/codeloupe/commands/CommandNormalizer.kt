package codeloupe.commands

import codeloupe.metrics.ShellCommand

/**
 * Sorts the calls of one run into groups: shell commands by [ShellCommand], file tools by tool name, a `Write` by what happened to
 * the file. The answer of a `Write` says it ("File created" or "has been updated"); steps stored without the answer fall back on the
 * run's history, as Claude Code makes an agent read a file before it overwrites it, so a path nothing touched before in the run
 * created the file. A path the summary cut short is not known and stays a plain `Write`. A rejected `Write` wrote nothing and is
 * counted apart: what it cost is waste. One instance per run: it remembers the paths.
 */
class CommandNormalizer {
    private val touched = HashSet<String>()

    /** [command] is the label of a shell call as the ingest stored it; calls stored before it get theirs from the summary. */
    fun of(tool: String, summary: String, answer: String? = null, failed: Boolean = false, command: String? = null): CommandLabel = when {
        tool in SHELLS -> (command ?: ShellCommand.of(summary)).let { CommandLabel(it, if (it.startsWith(ShellCommand.HEREDOC_PREFIX)) "heredoc" else "shell") }
        tool == "Write" -> CommandLabel(write(summary, answer, failed), "file")
        tool in FILE_TOOLS -> CommandLabel(tool, "file").also { touch(summary) }
        tool in SEARCH_TOOLS -> CommandLabel(tool, "search")
        tool in AGENT_TOOLS -> CommandLabel(tool, "agent")
        tool.startsWith("mcp__") -> CommandLabel(tool.removePrefix("mcp__").replace("__", "/"), "mcp")
        else -> CommandLabel(tool, "other")
    }

    private fun write(summary: String, answer: String?, failed: Boolean): String {
        if (failed) return "Write (rejected)"
        val path = key(summary)
        val firstTouch = path?.let(touched::add)
        return when {
            answer != null && answer.startsWith(CREATED) -> "Write (new file)"
            answer != null && UPDATED.containsMatchIn(answer) -> "Write (existing file)"
            firstTouch == null -> "Write"
            firstTouch -> "Write (new file)"
            else -> "Write (existing file)"
        }
    }

    private fun touch(summary: String) {
        key(summary)?.let(touched::add)
    }

    private fun key(path: String): String? = path.takeIf { it.isNotEmpty() && !it.endsWith("…") }?.replace('\\', '/')?.lowercase()

    private companion object {
        const val CREATED = "File created"
        val UPDATED = Regex("""has been (updated|overwritten)""")
        val SHELLS = setOf("Bash", "PowerShell")
        val FILE_TOOLS = setOf("Read", "Edit", "MultiEdit", "NotebookEdit")
        val SEARCH_TOOLS = setOf("Grep", "Glob")
        val AGENT_TOOLS = setOf("Agent", "Task", "SendMessage")
    }
}
