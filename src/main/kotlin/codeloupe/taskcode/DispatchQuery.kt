package codeloupe.taskcode

import codeloupe.doc.Doc
import codeloupe.doc.DocHash
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.ReadyTasks
import codeloupe.tracker.read.TaskFilter
import codeloupe.tracker.read.TaskList
import codeloupe.tracker.read.TaskRow
import codeloupe.tracker.read.TaskRows

/**
 * `dispatch_plan`: gathers the candidate tasks (given ids, an epic's open leaf tasks, a query, or the most urgent open
 * leaf tasks), their touch sets and the live worktrees, lets [DispatchPlan] group them, and renders the plan as a [Doc]
 * of sections `windows`, `waiting` and `live` so a repeated call costs one line when nothing changed.
 */
class DispatchQuery(private val trackers: Trackers, private val code: TaskCodeQuery) {
    class Request(val candidates: List<String>, val epic: String?, val query: String?, val slots: Int, val width: DispatchPlan.Width)

    suspend fun build(root: String, request: Request): Doc {
        val live = code.liveWindows(root)
        val held = live.flatMap { w -> w.tasks.map { it to w.branch } }.toMap()
        val (rows, notes) = candidates(request, held)
        if (rows.isEmpty() && notes.isEmpty()) return Doc.of("dispatch:none", emptyList())
        val touches = code.touches(root, rows.map { it.id } + live.flatMap { it.tasks }.distinct())
        val tasks = rows.map { row ->
            val files = touches[row.id]?.files.orEmpty()
            DispatchPlan.Task(row.id, row.summary, row.priority, row.priorityRank, light(row, files), row.blockers.toSet(), files)
        }
        // A window that just started has no diff yet; what its task is predicted to touch already counts.
        val liveSets = live.map { w ->
            DispatchPlan.Live("${w.tasks.joinToString("+").ifEmpty { w.branch }} (${w.path})", w.changed + w.tasks.flatMap { touches[it]?.files?.keys.orEmpty() })
        }
        val plan = DispatchPlan(request.width).plan(tasks, liveSets, request.slots)
        val byId = tasks.associateBy { it.id }
        val spec = listOf(request.candidates.joinToString(","), request.epic.orEmpty(), request.query.orEmpty(), request.slots, request.width).joinToString("|")
        return Doc.of("dispatch:${DocHash.of(spec)}", listOf(
            Triple("windows", "windows", windows(plan, byId, request)),
            Triple("waiting", "waiting", waiting(plan, notes)),
            Triple("live", "live", live(live, liveSets)),
        ).filter { it.third.lines().size > 1 })
    }

    /** Candidate rows, and the ones left out before planning with their reasons. */
    private fun candidates(request: Request, held: Map<String, String>): Pair<List<TaskRow>, List<DispatchPlan.Waiting>> {
        val left = ArrayList<DispatchPlan.Waiting>()
        val rows: List<TaskRow> = if (request.candidates.isNotEmpty()) {
            val found = request.candidates.mapNotNull { id -> trackers.mirror(id)?.let { (mirror, canonical) -> TaskRows.row(mirror.store, canonical) } }
            request.candidates.filter { id -> found.none { it.id.equals(trackers.mirror(id)?.second ?: id, ignoreCase = true) } }.forEach { left += DispatchPlan.Waiting(it, "not in the mirror") }
            found
        } else {
            val words = listOfNotNull(request.query, request.epic?.let { "epic: $it" }, "#unresolved", "sort: priority").joinToString(" ")
            trackers.mirrors.flatMap { TaskList.matching(it.store, TaskFilter.parse(words), ReadyTasks.LEAF) }
                .let { if (request.epic == null && request.query == null) it.take(DEFAULT_POOL) else it }
        }
        val (taken, free) = rows.partition { it.resolved || it.id.uppercase() in held }
        taken.forEach { left += DispatchPlan.Waiting(it.id, if (it.resolved) "already resolved" else "in a worktree already (${held[it.id.uppercase()]})") }
        return free to left
    }

    /** Light = small enough for a batch: a few files and a short text. */
    private fun light(row: TaskRow, files: Map<String, Char>): Boolean {
        val blocking = files.keys.filterNot { it.endsWith(".md") }
        val length = trackers.mirror(row.id)?.let { (mirror, id) -> mirror.store.issue(id)?.description?.length } ?: Int.MAX_VALUE
        return blocking.size in 1..LIGHT_FILES && length <= LIGHT_TEXT
    }

    private fun windows(plan: DispatchPlan.Result, byId: Map<String, DispatchPlan.Task>, request: Request): String = buildList {
        add("## windows")
        add("dispatch plan: ${plan.windows.size} windows of ${request.slots} slots (touch sets compared by ${request.width.name.lowercase()}), ${plan.waiting.size} waiting")
        plan.windows.forEachIndexed { i, w ->
            val steps = w.steps.joinToString(" → ") { it.joinToString("+") }
            add("W${i + 1} ${w.kind} $steps" + if (w.why.isEmpty()) " · clear of the others and of the live windows" else " · " + w.why.joinToString("; "))
            w.tasks.forEach { id -> byId[id]?.let { add("   " + taskLine(it)) } }
        }
        if (plan.unproven.isNotEmpty()) add("unproven (the text names no code, so it cannot be shown clear): ${plan.unproven.joinToString(", ")}")
    }.joinToString("\n")

    private fun taskLine(t: DispatchPlan.Task): String {
        val keys = t.blocking.sorted().take(KEY_FILES).map { "file:$it" } + t.blocking.map { it.substringBeforeLast('/', "") }.filter { it.isNotEmpty() }.distinct().sorted().take(KEY_DIRS).map { "dir:$it/" }
        val more = (t.blocking.size - KEY_FILES).takeIf { it > 0 }?.let { " (+$it files)" }.orEmpty()
        return "${t.id}${if (t.light) " light" else ""} ${t.priority ?: "-"} ${t.summary.take(SUMMARY)}  keys ${keys.joinToString(" ").ifEmpty { "-" }}$more"
    }

    private fun waiting(plan: DispatchPlan.Result, notes: List<DispatchPlan.Waiting>): String =
        (listOf("## waiting") + (plan.waiting + notes).map { "${it.id}  ${it.reason}" }).joinToString("\n")

    private fun live(live: List<TaskCodeQuery.LiveWindow>, sets: List<DispatchPlan.Live>): String =
        (listOf("## live") + live.zip(sets).map { (w, s) -> "${s.label} on ${w.branch}: ${s.files.size} files " + s.files.sorted().take(LIVE_FILES).joinToString(", ") }).joinToString("\n")

    private companion object {
        const val DEFAULT_POOL = 15
        const val LIGHT_FILES = 3
        const val LIGHT_TEXT = 2_000
        const val KEY_FILES = 4
        const val KEY_DIRS = 3
        const val SUMMARY = 70
        const val LIVE_FILES = 4
    }
}
