package codeloupe.tools

import codeloupe.doc.DocMemory
import codeloupe.jobs.JobRunner
import codeloupe.secrets.SecretAccess
import codeloupe.tracker.Trackers
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/** The tool catalog. Every tool also takes `root`, the repository or worktree to answer for. */
object Tools {
    val ALL: List<Tool> = listOf(FindTool, GrepTool, OutlineTool, SymbolTool, ContextTool, UsagesTool, CallsTool, HierarchyTool, ChangesTool)

    val ROOT: JsonObject = Schema.string(
        "Path to the repository or worktree to answer for (absolute). Defaults to the configured defaultRoot.",
    )

    fun named(name: String): Tool? = ALL.firstOrNull { it.name == name }

    /** The daemon's catalog: the code tools, the tracker tools when a tracker is configured, and task_code (history alone without one) and doc (text files). */
    fun catalog(trackers: Trackers, jobs: JobRunner? = null, secrets: SecretAccess? = null): List<Tool> {
        // The document reader's memory is shared: `doc` and `task_context` tell a caller the same "you already have this".
        val docs = DocMemory()
        val tracked = if (trackers.configured) listOf(IssueTool(trackers), TaskContextTool(trackers, docs), DispatchPlanTool(trackers, docs), TasksTool(trackers), SimilarTool(trackers), UpdateTool(trackers)) else emptyList()
        val handles = { handle: String -> jobs?.get(handle.removePrefix("job:"))?.log?.let { Path.of(it) } }
        return ALL + tracked + TaskCodeTool(trackers) + DocTool(docs, handles = handles) + listOfNotNull(jobs?.let { RunTool(it) }, secrets?.let { EnvTool(it) })
    }

    /** Tools that need no repository take `root` only as the caller's identity. */
    private val CALLER: JsonObject = Schema.string("Your worktree or repository (absolute): remembers what you already read.")

    /** `root` first, then the tool's own arguments. */
    fun properties(tool: Tool): JsonObject = JsonObject(linkedMapOf("root" to if (tool.needsRoot) ROOT else CALLER) + tool.properties)
}
