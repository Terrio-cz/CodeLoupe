package codeloupe.tools

import codeloupe.brain.BrainNotes
import codeloupe.brain.BrainService
import codeloupe.doc.DocFiles
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
        "Repository or worktree (absolute).",
    )

    fun named(name: String): Tool? = ALL.firstOrNull { it.name == name }

    /** The daemon's catalog: the code tools, the tracker tools when a tracker is configured, and task_code (history alone without one) and doc (text files). */
    fun catalog(
        trackers: Trackers, jobs: JobRunner? = null, secrets: SecretAccess? = null, home: Path? = null, docs: DocMemory = DocMemory(),
        brain: BrainService? = null, notes: BrainNotes? = null,
    ): List<Tool> {
        // The document reader's memory [docs] is shared: `doc` and `task_context` tell a caller the same "you already have this".
        val tracked = if (trackers.configured) listOf(IssueTool(trackers), TaskContextTool(trackers, docs), DispatchPlanTool(trackers, docs), TasksTool(trackers), SimilarTool(trackers), UpdateTool(trackers)) else emptyList()
        val handles = { handle: String -> jobs?.get(handle.removePrefix("job:"))?.log?.let { Path.of(it) } }
        // With a brain, symbol and context add one line of the brain entries anchored on the declaration; brain_brief, brain_search and brain_hygiene answer from it.
        val code = if (notes == null) ALL else ALL.map { if (it.name == "symbol" || it.name == "context") BrainAnnotatedTool(it, notes) else it }
        val brains = if (brain == null) emptyList() else listOf(BrainBriefTool(brain), BrainSearchTool(brain), BrainHygieneTool(brain))
        return code + tracked + brains + TaskCodeTool(trackers) + DocTool(docs, DocFiles(DocFiles.defaultRoots(), listOfNotNull(home)), handles) + listOfNotNull(jobs?.let { RunTool(it) }, secrets?.let { EnvTool(it) })
    }

    /** Tools that need no repository take `root` only as the caller's identity. */
    private val CALLER: JsonObject = Schema.string("Your worktree (absolute); keys what you already read.")

    /** `root` first, then the tool's own arguments. */
    fun properties(tool: Tool): JsonObject = JsonObject(linkedMapOf("root" to if (tool.needsRoot) ROOT else CALLER) + tool.properties)
}
