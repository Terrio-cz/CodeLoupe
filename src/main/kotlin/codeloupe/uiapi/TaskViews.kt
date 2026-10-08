package codeloupe.uiapi

import codeloupe.platform.IsoTime
import codeloupe.tracker.Criterion
import codeloupe.tracker.TrackerMirror
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.TaskFilter
import codeloupe.tracker.read.TaskList
import codeloupe.tracker.read.TaskRow
import codeloupe.tracker.read.TaskRows
import java.time.Instant
import java.util.Base64

/**
 * The Tasks screen, read from the tracker mirror only: a request never asks the tracker for anything, so the data is as
 * fresh as the mirror's last sync (`mirrorSyncedAt`).
 */
internal class TaskViews(private val trackers: Trackers, private val worktrees: WorktreeViews) {
    suspend fun page(project: String?, state: String?, q: String?, limit: String?, cursor: String?): TaskPage {
        val size = limit?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: throw UiApiException.badRequest("limit must be 1..$MAX_LIMIT") } ?: DEFAULT_LIMIT
        val offset = cursor?.let(::offsetOf) ?: 0
        val needle = q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val rows = trackers.mirrors.flatMap { m -> TaskList.matching(m.store, TaskFilter.parse(null)).map { m to it } }
            .filter { (_, r) ->
                (project == null || r.id.substringBefore('-').equals(project, ignoreCase = true)) &&
                    (state == null || stateOf(r).equals(state, ignoreCase = true)) &&
                    (needle == null || r.id.lowercase().contains(needle) || r.summary.lowercase().contains(needle))
            }
            .sortedByDescending { it.second.updated }
        val ids = byTask()
        val items = rows.drop(offset).take(size).map { (m, r) -> summary(m, r, ids) }
        val next = (offset + size).takeIf { it < rows.size }?.let(::cursorOf)
        return TaskPage(items, rows.size, next, mirrorSyncedAt())
    }

    suspend fun detail(id: String): TaskDetail {
        val (mirror, canonical) = trackers.mirror(id) ?: throw UiApiException.notFound("no task $id in the mirror")
        val store = mirror.store
        val issue = store.issue(canonical) ?: throw UiApiException.notFound("no task $id in the mirror")
        val row = TaskRows.row(store, canonical) ?: throw UiApiException.notFound("no task $id in the mirror")
        val own = worktrees.all().filter { it.taskId.equals(canonical, ignoreCase = true) }
        val base = summary(mirror, row, own.groupBy({ it.taskId!!.uppercase() }, { it.id }))
        val synced = store.state(issue.project).syncedAt
        val planning = listOfNotNull(
            issue.state?.let { TaskDetail.Field("State", it) }, issue.type?.let { TaskDetail.Field("Type", it) },
            issue.priority?.let { TaskDetail.Field("Priority", it) }, issue.assignee?.let { TaskDetail.Field("Assignee", it) },
        )
        val activity = buildList {
            add(TaskDetail.Activity(iso(issue.created), issue.reporter.orEmpty(), TaskDetail.ActivityKind.CREATED, "created"))
            store.comments(canonical).forEach { add(TaskDetail.Activity(iso(it.created), it.author.orEmpty(), TaskDetail.ActivityKind.COMMENT, it.text.take(MAX_TEXT))) }
            store.changes(canonical).forEach {
                val state = it.field.equals("State", ignoreCase = true)
                add(TaskDetail.Activity(iso(it.at), it.author.orEmpty(), if (state) TaskDetail.ActivityKind.STATE else TaskDetail.ActivityKind.FIELD, "${it.field}: ${it.removed.ifEmpty { "—" }} → ${it.added.ifEmpty { "—" }}"))
            }
        }.sortedByDescending { it.at }.take(MAX_ACTIVITY)
        return TaskDetail.of(
            base, url = "${mirror.instance.url.trimEnd('/')}/issue/${issue.id}", description = issue.description,
            fields = planning + issue.fields.map { TaskDetail.Field(it.name, it.value) },
            criteria = Criterion.parse(issue.description).map { TaskDetail.Criterion(it.text, it.done) },
            links = issue.links.map { TaskDetail.Link(it.verb, it.other, TaskRows.row(store, it.other)?.summary.orEmpty()) },
            activity = activity, worktrees = own, mirror = TaskDetail.Mirror(iso(synced ?: 0), null),
        )
    }

    /** The summary of task [id], or null when the mirror does not hold it. */
    suspend fun summaryOf(id: String): TaskSummary? {
        val (mirror, canonical) = trackers.mirror(id) ?: return null
        val row = TaskRows.row(mirror.store, canonical) ?: return null
        return summary(mirror, row, byTask())
    }

    fun openCount(): Int = trackers.mirrors.sumOf { TaskList.matching(it.store, TaskFilter.parse("#unresolved")).size }

    private suspend fun byTask(): Map<String, List<String>> =
        worktrees.all().filter { it.taskId != null }.groupBy({ it.taskId!!.uppercase() }, { it.id })

    private fun summary(mirror: TrackerMirror, row: TaskRow, ids: Map<String, List<String>>) = TaskSummary(
        id = row.id, project = row.id.substringBefore('-'), summary = row.summary, state = stateOf(row), priority = row.priority, type = row.type,
        assignee = mirror.store.issue(row.id)?.assignee, updatedAt = iso(row.updated), reads = 0, worktreeIds = ids[row.id.uppercase()].orEmpty(),
    )

    private fun stateOf(row: TaskRow) = row.state ?: if (row.resolved) "Resolved" else "Open"

    private fun mirrorSyncedAt(): String? = trackers.mirrors
        .flatMap { m -> m.instance.projects.mapNotNull { m.store.state(it).syncedAt } }.maxOrNull()?.let(::iso)

    private fun iso(epochMs: Long) = IsoTime.of(Instant.ofEpochMilli(epochMs))

    private fun cursorOf(offset: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString("o:$offset".toByteArray())

    private fun offsetOf(cursor: String): Int = runCatching {
        String(Base64.getUrlDecoder().decode(cursor)).removePrefix("o:").toInt().also { require(it >= 0) }
    }.getOrElse { throw UiApiException.badRequest("bad cursor") }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
        const val MAX_TEXT = 2_000
        const val MAX_ACTIVITY = 200
    }
}
