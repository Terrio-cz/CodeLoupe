package codeloupe.tools

import codeloupe.doc.DocMemory
import codeloupe.doc.DocReader
import codeloupe.repo.Registry
import codeloupe.taskcode.DispatchPlan
import codeloupe.taskcode.DispatchQuery
import codeloupe.taskcode.TaskCodeQuery
import codeloupe.tracker.Trackers

/** `dispatch_plan`: which ready tasks get a window now so that no two windows touch the same code. */
class DispatchPlanTool(
    private val trackers: Trackers,
    memory: DocMemory,
    private val initialWaitMs: Long = 10_000,
    private val staleWaitMs: Long = 3_000,
) : Tool {
    private val reader = DocReader(memory)
    override val name = "dispatch_plan"
    override val description = "Plan the next windows of work: from candidates=[ids], epic=<id>, or query=<filters> (none: the most urgent open leaf " +
        "tasks) it predicts each task's touch set (landed files, else the code its text names), compares them with each other and with the " +
        "live worktrees, and answers windows — a batch of up to 3 light tasks, a chain of up to 3 steps, or one task — each with the shared " +
        "code that put its tasks together and per-task keys (file:/dir:), then what waits and why (fights with a live worktree, unmet " +
        "dependency, no free slot) and the live windows. slots = free windows (default 4); width=dir (default) also treats one directory as a " +
        "clash, file only one file. replay=true plans past work again (resolved tasks with their landed files, no live windows) to check a recorded dispatch. Asking again answers 'unchanged' or only the changed sections. You keep the final judgment."
    override val properties = Schema.properties(
        "candidates" to Schema.strings("Task ids to place, e.g. [TER-5, TER-6]"),
        "epic" to Schema.string("Epic id: its open leaf tasks are the candidates"),
        "query" to Schema.string("Tracker filters like the tasks tool, e.g. 'project: TER priority: Major'"),
        "slots" to Schema.integer(0, 8),
        "width" to Schema.enum(listOf("dir", "file")),
        "replay" to Schema.boolean("Plan past work again: resolved tasks count with the files they landed, live worktrees are ignored"),
        "sections" to Schema.strings("windows, waiting, live"),
        "view" to Schema.enum(listOf("full", "digest")),
        "since" to Schema.string("none: send everything again"),
    )
    override val required = emptyList<String>()

    private var query: DispatchQuery? = null

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val built = synchronized(this) { query ?: DispatchQuery(trackers, TaskCodeQuery(registry, trackers, initialWaitMs, staleWaitMs)).also { query = it } }
        val request = DispatchQuery.Request(
            args.strings("candidates").map { it.uppercase() }, args.string("epic")?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(), args.string("query")?.trim()?.takeIf { it.isNotEmpty() },
            args.int("slots") ?: DEFAULT_SLOTS, if (args.string("width") == "file") DispatchPlan.Width.FILE else DispatchPlan.Width.DIR, args.bool("replay") == true,
        )
        val doc = built.build(root, request)
        val view = if (args.string("view") == "digest") DocReader.View.DIGEST else DocReader.View.FULL
        if (doc.sections.isEmpty()) return "dispatch plan: nothing to place (no open leaf task matches)"
        return reader.read(Sessions.key(root), doc, view, args.strings("sections"), forget = args.string("since") == "none")
    }

    private companion object {
        const val DEFAULT_SLOTS = 4
    }
}
