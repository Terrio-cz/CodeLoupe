package codeloupe.tools

import codeloupe.tracker.Trackers
import kotlinx.serialization.json.JsonObject

/** The tool catalog. Every tool also takes `root`, the repository or worktree to answer for. */
object Tools {
    val ALL: List<Tool> = listOf(FindTool, OutlineTool, SymbolTool, UsagesTool, CallsTool, HierarchyTool, ChangesTool)

    val ROOT: JsonObject = Schema.string(
        "Path to the repository or worktree to answer for (absolute). Defaults to the configured defaultRoot.",
    )

    fun named(name: String): Tool? = ALL.firstOrNull { it.name == name }

    /** The daemon's catalog: the code tools, plus the tracker tools when a tracker is configured. */
    fun catalog(trackers: Trackers): List<Tool> =
        ALL + if (trackers.configured) listOf(IssueTool(trackers), TasksTool(trackers)) else emptyList()

    /** Tools that need no repository take `root` only as the caller's identity. */
    private val CALLER: JsonObject = Schema.string("Your worktree or repository (absolute): remembers what you already read.")

    /** `root` first, then the tool's own arguments. */
    fun properties(tool: Tool): JsonObject = JsonObject(linkedMapOf("root" to if (tool.needsRoot) ROOT else CALLER) + tool.properties)
}
