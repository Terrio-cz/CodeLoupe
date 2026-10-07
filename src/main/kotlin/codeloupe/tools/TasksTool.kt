package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.tracker.TrackerMirror
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.EpicProgress
import codeloupe.tracker.read.ReadyTasks
import codeloupe.tracker.read.TaskFilter
import codeloupe.tracker.read.TaskGraph
import codeloupe.tracker.read.TaskList
import codeloupe.tracker.read.WorktreeHolds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** `tasks`: queries over the local tracker mirror — a filtered list, a task's graph, ready tasks, an epic's progress. */
class TasksTool(private val trackers: Trackers, private val initialWaitMs: Long = 10_000, private val staleWaitMs: Long = 3_000) : Tool {
    override val name = "tasks"
    override val description = "Tasks from the local tracker mirror, one line each. mode=list (default): query = filters (project: TER, " +
        "state: {In Progress}, state: -Done, #unresolved, epic: TER-1, type: Bug, any field: value, sort: created|id|priority) and " +
        "full-text words. graph: query = an issue id; epic, dependencies, subtasks, relations. ready: open leaf tasks in the query's " +
        "scope (add state: to leave out started ones) with resolved dependencies, not on a git worktree branch. progress: query = epic id(s)."
    override val properties = Schema.properties(
        "query" to Schema.string(),
        "mode" to Schema.enum(listOf("list", "graph", "ready", "progress")),
        "depth" to Schema.integer(1, 3),
        "limit" to Schema.integer(1, 200),
    )
    override val required = emptyList<String>()
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val query = args.string("query")?.trim().orEmpty()
        val limit = args.int("limit") ?: 40
        val note = trackers.current(initialWaitMs, staleWaitMs)
        val answer = withContext(Dispatchers.IO) {
            when (args.string("mode") ?: "list") {
                "graph" -> byId(query) { mirror, id -> TaskGraph.render(mirror.store, id, args.int("depth") ?: 1, limit) }
                "progress" -> query.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.ifEmpty { listOf("") }
                    .joinToString("\n\n") { id -> byId(id) { mirror, epic -> EpicProgress.render(mirror.store, epic, limit) } }
                "ready" -> {
                    val held = WorktreeHolds.held(repos(registry))
                    trackers.mirrors.joinToString("\n") { ReadyTasks.render(it.store, TaskFilter.parse(query), held, limit) }
                }
                else -> TaskList.render(trackers.mirrors.flatMap { TaskList.matching(it.store, TaskFilter.parse(query)) }, limit)
            }
        }
        return if (note == null) answer else "$answer\n$note"
    }

    private fun byId(id: String, render: (TrackerMirror, String) -> String): String {
        val (mirror, canonical) = trackers.mirror(id) ?: return "pass an issue id of a mirrored project (${trackers.projects().joinToString(", ")}) as query"
        return render(mirror, canonical)
    }

    /** Repositories whose worktree branches hold tasks: the configured ones and every repository the daemon has indexed. */
    private fun repos(registry: Registry): List<String> =
        (trackers.mirrors.flatMap { it.instance.repos } + registry.snapshot().map { it.commonDir })
            .filter { runCatching { Files.isDirectory(Path.of(it)) }.getOrDefault(false) }
}
