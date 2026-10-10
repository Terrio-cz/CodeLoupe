package codeloupe.tools

import codeloupe.doc.DocMemory
import codeloupe.doc.DocReader
import codeloupe.repo.Registry
import codeloupe.taskcode.TaskCodeQuery
import codeloupe.taskcode.TaskContext
import codeloupe.tracker.Trackers

/** `task_context`: the planner's starting pack for a task in one call, and on a repeated call only what changed. */
class TaskContextTool(
    private val trackers: Trackers,
    memory: DocMemory,
    private val initialWaitMs: Long = 10_000,
    private val staleWaitMs: Long = 3_000,
) : Tool {
    // A pack for a big task runs past the 20k of an ordinary document; cutting its tail would drop the norms and the prior tasks.
    private val reader = DocReader(memory, maxChars = 26_000)
    override val name = "task_context"
    override val description = "Everything to start planning a task, in one call instead of issue + tasks + task_code + search: sections " +
        "issue (brief with criteria), description (its sections, capped), comments (the thread, capped), linked (dependencies and relations with state), " +
        "open-criteria (what related open tasks still owe), touch (landed, in a worktree, or predicted code), declarations (outline lines of those files), " +
        "callers (files that reference them), prior (earlier tasks that changed them, with landing commit), cochange (files those tasks changed along with them that the task does not name yet), norms (AGENTS.md lines and docs that bear on them). sections=[…] picks some; view=digest lists them with sizes. The same root asking again gets 'unchanged' in " +
        "one line, or only the sections that changed; since=none sends everything again."
    override val properties = Schema.properties(
        "id" to Schema.string("e.g. TER-5"),
        "sections" to Schema.strings("issue, description, comments, linked, open-criteria, touch, declarations, callers, prior, cochange, norms"),
        "view" to Schema.enum(listOf("full", "digest")),
        "since" to Schema.string("none: send everything again"),
    )
    override val required = listOf("id")

    private var context: TaskContext? = null

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val id = args.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return "pass id: a task id like TER-5"
        val built = synchronized(this) { context ?: TaskContext(registry, trackers, TaskCodeQuery(registry, trackers, initialWaitMs, staleWaitMs)).also { context = it } }
        val doc = built.build(root, id)
        val view = if (args.string("view") == "digest") DocReader.View.DIGEST else DocReader.View.FULL
        return reader.read(Sessions.key(root), doc, view, args.strings("sections"), forget = args.string("since") == "none")
    }
}
