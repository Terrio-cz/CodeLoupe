package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.taskcode.TaskCodeQuery
import codeloupe.tracker.Trackers

/** `task_code`: a task's code (landed or predicted), or a declaration's or file's tasks. */
class TaskCodeTool(private val trackers: Trackers, private val initialWaitMs: Long = 10_000, private val staleWaitMs: Long = 3_000) : Tool {
    override val name = "task_code"
    override val description = "Links between tasks and code. query = a task id: the code it landed on the default branch (landing commit, " +
        "files, changed declarations + ~ ^ -), the worktree working on it, and for an open task the touch set predicted from its " +
        "text (= sure · ~ likely · ? guess · + new, each with the issue text it comes from). query = a declaration (Type.member) or a " +
        "path: the tasks that changed it, newest first, with landing commits and marks, plus open tasks predicted to touch it."
    override val properties = Schema.properties(
        "query" to Schema.string("A task id (TER-5), a declaration (Type.member, pkg.Type, File.kt:line) or a file path"),
        "limit" to Schema.integer(1, 500),
    )
    override val required = listOf("query")

    private var query: TaskCodeQuery? = null

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val q = args.string("query")?.trim().orEmpty()
        if (q.isEmpty()) return "pass query: a task id, a declaration or a file path"
        val query = synchronized(this) { query ?: TaskCodeQuery(registry, trackers, initialWaitMs, staleWaitMs).also { query = it } }
        return query.answer(root, q, (args.int("limit") ?: 60).coerceIn(1, 500))
    }
}
