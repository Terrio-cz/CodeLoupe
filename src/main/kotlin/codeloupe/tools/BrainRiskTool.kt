package codeloupe.tools

import codeloupe.brain.BrainException
import codeloupe.brain.BrainService
import codeloupe.repo.Registry

/**
 * `brain_risk`: what `node brain/brain.mjs risk <files> --json` answers, in one MCP call. The daemon starts the workspace's own engine, which reads the
 * weights from `workspace.json` and asks this daemon for the scores ([codeloupe.brain.BrainRisk]), so the answer has the engine's shape and a daemon
 * that cannot be asked still yields the engine's own, code-index-free score.
 */
class BrainRiskTool(private val brain: BrainService) : Tool {
    override val name = "brain_risk"
    override val description = "Where earlier tasks found problems in the code a task is about to touch: for each file and its module a score (findings weighted by severity and recency) " +
        "and the brain entries that make it up, as JSON (brain.mjs risk --json): files[{path, module, score, level none|low|medium|high, entries[]}], modules[], max, level. " +
        "files = the planned or changed files. as_of replays a past day. Without the daemon, node brain/brain.mjs risk <files> scores by the files the entries name."
    override val needsRoot = false
    override val properties = Schema.properties(
        "files" to Schema.strings("planned or changed files (paths or file names)"),
        "workspace" to Schema.string("the workspace directory holding brain/brain.mjs; default the one you work in, else the only one"),
        "limit" to Schema.integer(1, 100),
        "as_of" to Schema.string("score as of this date or date-time (UTC); entries written later are left out"),
        "code_root" to Schema.string("checkout to read the files from (a task worktree); default the workspace's canonical repository"),
    )
    override val required = listOf("files")

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val files = args.strings("files").map { it.trim().trimStart('-') }.filter { it.isNotEmpty() }.map { it.take(MAX_FILE) }.take(MAX_FILES)
        if (files.isEmpty()) return "pass files: the paths the task will touch"
        return try {
            val workspace = brain.pick(args.string("workspace"), root)
            val engine = buildList {
                add("risk")
                addAll(files)
                args.int("limit")?.let { add("--limit"); add(it.coerceIn(1, 100).toString()) }
                args.string("as_of")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("-") }?.let { add("--as-of"); add(it) }
                args.string("code_root")?.takeIf { it.isNotBlank() }?.let { add("--code-root"); add(it) }
                add("--json")
            }
            brain.engine(workspace, engine)
        } catch (e: BrainException) {
            "brain_risk failed: ${e.message}. node brain/brain.mjs risk answers without the daemon."
        }
    }

    private companion object {
        const val MAX_FILES = 200
        const val MAX_FILE = 300
    }
}
