package codeloupe.commands

/**
 * Sorts the calls of one run into groups: shell commands by [ShellCommand], file tools by tool name, a `Write` by whether the file
 * existed. Claude Code makes an agent read a file before it overwrites it, so a `Write` to a path nothing in the run touched before
 * created it; a path the summary cut short is not known and stays a plain `Write`. One instance per run: it remembers the paths.
 */
class CommandNormalizer {
    private val touched = HashSet<String>()

    fun of(tool: String, summary: String): CommandLabel = when {
        tool in SHELLS -> ShellCommand.of(summary).let { CommandLabel(it, if (it.startsWith(ShellCommand.HEREDOC_PREFIX)) "heredoc" else "shell") }
        tool == "Write" -> CommandLabel(write(summary), "file")
        tool in FILE_TOOLS -> CommandLabel(tool, "file").also { touch(summary) }
        tool in SEARCH_TOOLS -> CommandLabel(tool, "search")
        tool in AGENT_TOOLS -> CommandLabel(tool, "agent")
        tool.startsWith("mcp__") -> CommandLabel(tool.removePrefix("mcp__").replace("__", "/"), "mcp")
        else -> CommandLabel(tool, "other")
    }

    private fun write(summary: String): String {
        val path = key(summary) ?: return "Write"
        return if (touched.add(path)) "Write (new file)" else "Write (existing file)"
    }

    private fun touch(summary: String) {
        key(summary)?.let(touched::add)
    }

    private fun key(path: String): String? = path.takeIf { it.isNotEmpty() && !it.endsWith("…") }?.replace('\\', '/')?.lowercase()

    private companion object {
        val SHELLS = setOf("Bash", "PowerShell")
        val FILE_TOOLS = setOf("Read", "Edit", "MultiEdit", "NotebookEdit")
        val SEARCH_TOOLS = setOf("Grep", "Glob")
        val AGENT_TOOLS = setOf("Agent", "Task", "SendMessage")
    }
}
