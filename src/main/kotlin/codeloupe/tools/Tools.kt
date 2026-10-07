package codeloupe.tools

import kotlinx.serialization.json.JsonObject

/** The tool catalog. Every tool also takes `root`, the repository or worktree to answer for. */
object Tools {
    val ALL: List<Tool> = listOf(FindTool, OutlineTool, SymbolTool, UsagesTool, CallsTool, HierarchyTool, ChangesTool)

    val ROOT: JsonObject = Schema.string(
        "Path to the repository or worktree to answer for (absolute). Defaults to the configured defaultRoot.",
    )

    fun named(name: String): Tool? = ALL.firstOrNull { it.name == name }

    /** `root` first, then the tool's own arguments. */
    fun properties(tool: Tool): JsonObject = JsonObject(linkedMapOf("root" to ROOT) + tool.properties)
}
