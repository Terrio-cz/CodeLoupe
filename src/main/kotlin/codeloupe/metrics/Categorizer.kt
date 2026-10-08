package codeloupe.metrics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sorts tool calls into categories by ordered [CategoryRule]s; a call no rule takes is `other`. */
class Categorizer(private val rules: List<CategoryRule>) {
    /** The rules as text: stored categories are stale when it changes. */
    val fingerprint: String = rules.joinToString("\n")

    fun categorize(name: String, input: JsonObject): String {
        val file = (input["file_path"] as? JsonPrimitive)?.content.orEmpty()
        val command = (input["command"] as? JsonPrimitive)?.content.orEmpty().trim().replaceFirst(LEADING_CD, "")
        return rules.firstOrNull { it.matches(name, file, command) }?.category ?: "other"
    }

    companion object {
        private val LEADING_CD = Regex("""^cd\s+\S+\s*(&&|;)\s*""")
        private const val CODE_FILE = """(?i)\.(kt|kts|java)$"""
        private const val SHELL_SEARCH = """^(rg|grep|cat|sed|head|tail|awk|find|ls|wc|less|type|Get-Content|Select-String)\b"""
        private const val SHELL = "^(Bash|PowerShell)$"

        /** The categories of the CL-31 baseline, shell commands of the Terrio workspace included. */
        val DEFAULT_RULES: List<CategoryRule> = listOf(
            CategoryRule("code_tool_write", tool = """^mcp__code__.*(replace|insert|delete|add_imports|create_file)"""),
            CategoryRule("code_tool_read", tool = "^mcp__code__"),
            CategoryRule("code_index", tool = "^mcp__(idea|gitnexus)__"),
            CategoryRule("codeloupe", tool = "^mcp__codeloupe__"),
            CategoryRule("youtrack", tool = "^mcp__youtrack__"),
            CategoryRule("db", tool = "^mcp__datagrip__"),
            CategoryRule("code_read", tool = "^Read$", file = CODE_FILE),
            CategoryRule("doc_read", tool = "^Read$"),
            CategoryRule("code_search", tool = "^(Grep|Glob)$"),
            CategoryRule("code_write", tool = "^(Edit|Write|MultiEdit)$", file = CODE_FILE),
            CategoryRule("other_write", tool = "^(Edit|Write|MultiEdit)$"),
            CategoryRule("agents", tool = "^(Agent|SendMessage)$"),
            CategoryRule("codeloupe", tool = SHELL, command = """^(\S*[\\/])?codeloupe(\.bat)?\s"""),
            CategoryRule("brain", tool = SHELL, command = """brain/brain\.mjs|brain\.mjs"""),
            CategoryRule("build_test", tool = SHELL, command = """terrio\.mjs\s+(gradle|ci)\b|gradlew"""),
            CategoryRule("stack", tool = SHELL, command = """terrio\.mjs\s+(docker|api|db|evidence|openapi)\b|docker\s"""),
            CategoryRule("git_read", tool = SHELL, command = """^git\b.*\b(diff|show|log|blame|grep)\b|git -C \S+ (diff|show|log|blame|grep)\b"""),
            CategoryRule("git", tool = SHELL, command = """^git\b|git -C|terrio\.mjs\s+git\b"""),
            CategoryRule("code_search_shell", tool = SHELL, command = SHELL_SEARCH),
            CategoryRule("shell_other", tool = SHELL),
        )
    }
}
