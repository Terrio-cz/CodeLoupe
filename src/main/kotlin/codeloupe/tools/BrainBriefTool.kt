package codeloupe.tools

import codeloupe.brain.BrainException
import codeloupe.brain.BrainService
import codeloupe.repo.Registry

/**
 * `brain_brief`: what `node brain/brain.mjs brief` answers, in one MCP call, ranked by the daemon's code index. The daemon starts the workspace's own
 * engine with `--json` (state, settled findings, the task's notes, decisions, facts) and the engine asks this daemon for the ranking, so the answer has the
 * shape of `brief --json` and a brain that is not indexed yet still answers.
 */
class BrainBriefTool(private val brain: BrainService) : Tool {
    override val name = "brain_brief"
    override val description = "Start of a role in a workspace that has a shared-memory brain: its state, SETTLED findings, the task's notes, decisions on the code and the facts " +
        "that role reads, in one call, whole entries as JSON (brain.mjs brief --json). Entries tied to the files and symbols you name (anchors), to their callers and module, " +
        "or to tasks that changed that code come first, text matches after. files = planned or changed files, terms = classes, change kinds. " +
        "Without the daemon, node brain/brain.mjs brief <role> <task> <terms> answers the same by terms alone."
    override val needsRoot = false
    override val properties = Schema.properties(
        "role" to Schema.string("planner, coder, reviewer, deep-reviewer, tester, steward, retro (the workspace's brain/workspace.json briefs)"),
        "task" to Schema.string("task id, e.g. TER-5: adds its state and notes"),
        "files" to Schema.strings("planned or changed files (paths or file names)"),
        "terms" to Schema.strings("classes, change kinds, words"),
        "workspace" to Schema.string("the workspace directory holding brain/brain.mjs; default the one you work in, else the only one"),
        "limit" to Schema.integer(1, 200),
        "all" to Schema.boolean("also superseded entries"),
        "code_root" to Schema.string("checkout to read the files from (a task worktree); default the workspace's canonical repository"),
    )
    override val required = listOf("role")

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val role = args.string("role")?.trim().orEmpty()
        if (role.isEmpty() || role.startsWith("-")) return "pass role: the brain's roles are in brain/workspace.json briefs"
        return try {
            val workspace = brain.pick(args.string("workspace"), root)
            val engine = buildList {
                add("brief")
                add(role)
                args.string("task")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("-") }?.let(::add)
                addAll(words(args.strings("terms")))
                val files = words(args.strings("files"))
                if (files.isNotEmpty()) { add("--files"); add(files.joinToString(",")) }
                args.int("limit")?.let { add("--limit"); add(it.coerceIn(1, 200).toString()) }
                if (args.bool("all") == true) add("--all")
                args.string("code_root")?.takeIf { it.isNotBlank() }?.let { add("--code-root"); add(it) }
                add("--json")
            }
            brain.engine(workspace, engine)
        } catch (e: BrainException) {
            "brain_brief failed: ${e.message}. node brain/brain.mjs brief answers without the daemon."
        }
    }

    // A term that starts with "-" would be read as an option by the engine.
    private fun words(raw: List<String>): List<String> = raw.map { it.trim().trimStart('-') }.filter { it.isNotEmpty() }.map { it.take(MAX_TERM) }.take(MAX_TERMS)

    private companion object {
        const val MAX_TERMS = 60
        const val MAX_TERM = 200
    }
}
