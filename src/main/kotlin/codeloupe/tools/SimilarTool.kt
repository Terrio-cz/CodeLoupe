package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.Similar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `similar`: tasks of the local mirror that look like a draft issue; call it before creating one. */
class SimilarTool(private val trackers: Trackers, private val initialWaitMs: Long = 10_000, private val staleWaitMs: Long = 3_000) : Tool {
    override val name = "similar"
    override val description = "Before you create an issue: the tasks of the local tracker mirror (open, or resolved in the last 90 days) that talk about " +
        "the same thing as your draft summary and description, best full-text match first, with the words they share. If one is the same work, " +
        "extend or comment on it, or link the new issue with 'relates to'. Answers in well under 100 ms from the mirror."
    override val properties = Schema.properties(
        "summary" to Schema.string("Title of the issue you are about to create"),
        "description" to Schema.string("Its description or scope, if you have one"),
        "project" to Schema.string("Only this project's mirror, e.g. TER"),
        "limit" to Schema.integer(1, 20),
    )
    override val required = listOf("summary")
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val summary = args.string("summary")?.trim().orEmpty()
        if (summary.isEmpty()) return "pass the draft summary"
        val project = args.string("project")?.trim()?.takeIf { it.isNotEmpty() }
        val mirrors = trackers.mirrors.filter { m -> project == null || m.instance.projects.any { it.equals(project, ignoreCase = true) } }
        if (mirrors.isEmpty()) return "no tracker mirrors ${project ?: "any project"}; mirrored: ${trackers.projects().joinToString(", ")}"
        val note = trackers.current(initialWaitMs, staleWaitMs)
        val limit = args.int("limit") ?: 5
        val answer = withContext(Dispatchers.IO) {
            Similar.render(mirrors.flatMap { Similar.find(it.store, summary, args.string("description").orEmpty(), limit) }.take(limit))
        }
        return if (note == null) answer else "$answer\n$note"
    }
}
