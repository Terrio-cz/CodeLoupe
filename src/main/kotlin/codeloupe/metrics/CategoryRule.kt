package codeloupe.metrics

/**
 * Puts a tool call into [category] when every pattern it sets matches: [tool] the tool name, [file] the call's
 * `file_path`, [command] the shell command (a leading `cd dir &&` dropped). Rules are tried in order, the first wins.
 */
class CategoryRule(val category: String, tool: String? = null, file: String? = null, command: String? = null) {
    private val tool = tool?.let(::Regex)
    private val file = file?.let(::Regex)
    private val command = command?.let(::Regex)
    private val spec = listOf(category, tool, file, command).joinToString("|")

    /** The rule's patterns, so a changed rule set can be told from an unchanged one. */
    override fun toString(): String = spec

    fun matches(name: String, filePath: String, shellCommand: String): Boolean =
        (tool == null || tool.containsMatchIn(name)) && (file == null || file.containsMatchIn(filePath)) &&
            (command == null || command.containsMatchIn(shellCommand))
}
