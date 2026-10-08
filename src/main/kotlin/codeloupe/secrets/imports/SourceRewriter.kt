package codeloupe.secrets.imports

/**
 * Takes imported values out of a source file and leaves a reference behind. A dotenv statement becomes a comment (a
 * program that still reads the file fails loudly instead of receiving a placeholder as if it were the value); a JSON string
 * becomes `${NAME}`, which Claude Code expands in MCP configs and which `codeloupe env run` provides. Everything else in the
 * file stays byte for byte.
 */
object SourceRewriter {
    private val REFERENCE = Regex("^\\$\\{[A-Za-z_][A-Za-z0-9_]*(:-[^}]*)?}$")

    fun isReference(value: String): Boolean = REFERENCE.matches(value.trim())

    fun replacement(variable: FoundVariable): String = when (variable.kind) {
        SourceKind.DOTENV, SourceKind.DOCKER_ENV -> "# ${variable.name} moved to the CodeLoupe store; start the program with `codeloupe env run`"
        SourceKind.CLAUDE_SETTINGS, SourceKind.MCP_CONFIG, SourceKind.CLAUDE_JSON -> "\"\${${variable.name}}\""
    }

    /** [text] with every one of [variables] (all from this text) replaced. */
    fun rewrite(text: String, variables: List<FoundVariable>): String {
        val out = StringBuilder(text)
        variables.sortedByDescending { it.start }.forEach { out.replace(it.start, it.end, replacement(it)) }
        return out.toString()
    }
}
