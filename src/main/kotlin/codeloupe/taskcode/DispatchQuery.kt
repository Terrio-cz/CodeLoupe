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
 * leaf tasks that nothing holds, blocks or excludes, of the project a workspace root owns), their touch sets and the live worktrees, lets [DispatchPlan] group them, and renders the plan as a [Doc]
 * of sections `windows`, `waiting` and `live` so a repeated call costs one line when nothing changed.
 */
class DispatchQuery(private val trackers: Trackers, private val code: TaskCodeQuery, private val scopes: DispatchScopes = DispatchScopes.NONE) {
    /**
     * The tracker rows behind the plan: [taken] ones were left out before planning ([DispatchPlan.Waiting] in [left]). [skipped] summarises
     * the default pool's rows dropped before it was cut, [more] the plannable ones the cut left unseen.
     */
    private data class Candidates(val free: List<TaskRow>, val taken: List<TaskRow>, val left: List<DispatchPlan.Waiting>, val skipped: List<String> = emptyList(), val more: Int = 0)

    /** [exclude]: task ids the caller knows are taken (a brain claim, a standing blocker, a window dispatched but not started). */
    class Request(
        val candidates: List<String>, val epic: String?, val query: String?, val slots: Int, val width: DispatchPlan.Width, val replay: Boolean = false,
        val exclude: List<String> = emptyList(),
    )

    suspend fun build(requestRoot: String, request: Request): Doc {
        val scope = scopes.of(requestRoot)
        // A workspace that is not the code reads the worktrees and landed files of the repository its tasks land in.
        val root = scope?.codeRoot ?: requestRoot
        // A replay plans past work again: resolved tasks count, with the files they landed, and no live window blocks.
        val live = if (request.replay) emptyList() else code.liveWindows(root)
        val held = live.flatMap { w -> w.tasks.map { it to w.branch } }.toMap()
        val (rows, taken, notes, skipped, more) = candidates(request, held, scope)
        if (rows.isEmpty() && notes.isEmpty() && skipped.isEmpty()) return Doc.of("dispatch:none", emptyList())
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
        val states = (rows + taken).associateBy { it.id.uppercase() }
        val spec = listOf(request.candidates.joinToString(","), request.epic.orEmpty(), request.query.orEmpty(), request.slots, request.width, request.replay, request.exclude.joinToString(","), scope?.projects?.joinToString(",").orEmpty()).joinToString("|")
        return Doc.of("dispatch:${DocHash.of(spec)}", listOf(
            Triple("windows", "windows", windows(plan, byId, states, request)),
            Triple("waiting", "waiting", waiting(plan, notes, states, skipped + listOfNotNull(more.takeIf { it > 0 }?.let { "$it more plannable tasks not shown (pool of ${pool(request.slots)}); name them with candidates=[…] or narrow query=" }))),
            Triple("live", "live", live(live, liveSets)),
        ).filter { it.third.lines().size > 1 })
    }

    /** Candidate rows, and the ones left out before planning with their reasons. */
    private fun candidates(request: Request, held: Map<String, String>, scope: DispatchScope?): Candidates {
        val left = ArrayList<DispatchPlan.Waiting>()
        val excluded = request.exclude.map { it.uppercase() }.toSet()
        var skipped = emptyList<String>()
        var more = 0
        val rows: List<TaskRow> = if (request.candidates.isNotEmpty()) {
            val found = request.candidates.mapNotNull { id -> trackers.mirror(id)?.let { (mirror, canonical) -> TaskRows.row(mirror.store, canonical) } }
            request.candidates.filter { id -> found.none { it.id.equals(trackers.mirror(id)?.second ?: id, ignoreCase = true) } }.forEach { left += DispatchPlan.Waiting(it, "not in the mirror") }
            found
        } else {
            // Without a project of its own, a request from a workspace stays in the project that workspace owns.
            val project = scope?.projects?.takeIf { it.isNotEmpty() && request.epic == null && !PROJECT_TERM.containsMatchIn(request.query.orEmpty()) }?.joinToString(", ", "project: ")
            val words = listOfNotNull(request.query, request.epic?.let { "epic: $it" }, project, "#unresolved", "sort: priority").joinToString(" ")
            val found = trackers.mirrors.flatMap { TaskList.matching(it.store, TaskFilter.parse(words), ReadyTasks.LEAF) }
            if (request.epic == null && request.query == null) {
                // The pool holds plannable tasks only, most urgent first: cutting it first would fill it with what cannot be planned.
                val plannable = found.sortedBy { it.priorityRank ?: Int.MAX_VALUE }.filter { Dropped.of(it, held, excluded) == null }
                skipped = skipped(found, held, excluded)
                more = (plannable.size - pool(request.slots)).coerceAtLeast(0)
                plannable.take(pool(request.slots))
            } else {
                found
            }
        }
        val (taken, free) = rows.partition { (it.resolved && !request.replay) || it.id.uppercase() in held || it.id.uppercase() in excluded }
        taken.forEach { left += DispatchPlan.Waiting(it.id, if (it.resolved) "already resolved" else if (it.id.uppercase() in excluded) "left out by the caller (exclude)" else "in a worktree already (${held[it.id.uppercase()]})") }
        return Candidates(free, taken, left, skipped, more)
    }

    /** How many rows the default pool takes: enough that every free slot can find a window of its own. */
    private fun pool(slots: Int) = maxOf(DEFAULT_POOL, POOL_PER_SLOT * slots)

    /** One line saying how many rows were dropped from the default pool before it was cut, by reason. */
    private fun skipped(found: List<TaskRow>, held: Map<String, String>, excluded: Set<String>): List<String> {
        val counts = found.mapNotNull { Dropped.of(it, held, excluded) }.groupingBy { it }.eachCount()
        return if (counts.isEmpty()) emptyList() else listOf("left out of the pool: " + Dropped.entries.filter { it in counts }.joinToString(", ") { "${counts.getValue(it)} ${it.label}" })
    }

    /** Why a row of the default pool cannot be planned. */
    private enum class Dropped(val label: String) {
        EXCLUDED("left out by the caller"),
        TAKEN("in a worktree or in progress/review"),
        BLOCKED("waiting on a dependency");

        companion object {
            private val STARTED = Regex("progress|review|testing", RegexOption.IGNORE_CASE)

            fun of(row: TaskRow, held: Map<String, String>, excluded: Set<String>): Dropped? = when {
                row.id.uppercase() in excluded -> EXCLUDED
                row.id.uppercase() in held || row.state?.let(STARTED::containsMatchIn) == true -> TAKEN
                row.blockers.isNotEmpty() -> BLOCKED
                else -> null
            }
        }
    }

    /** Light = small enough for a batch: a few files and a short text. */
    private fun light(row: TaskRow, files: Map<String, Char>): Boolean {
        val blocking = files.keys.filterNot { it.endsWith(".md") }
        val length = trackers.mirror(row.id)?.let { (mirror, id) -> mirror.store.issue(id)?.description?.length } ?: Int.MAX_VALUE
        return blocking.size in 1..LIGHT_FILES && length <= LIGHT_TEXT
    }

    private fun windows(plan: DispatchPlan.Result, byId: Map<String, DispatchPlan.Task>, rows: Map<String, TaskRow>, request: Request): String = buildList {
        add("## windows")
        add("dispatch plan: ${plan.windows.size} windows of ${request.slots} slots (touch sets compared by ${request.width.name.lowercase()}), ${plan.waiting.size} waiting")
        plan.windows.forEachIndexed { i, w ->
            val steps = w.steps.joinToString(" → ") { it.joinToString("+") }
            add("W${i + 1} ${w.kind} $steps" + if (w.why.isEmpty()) " · clear of the others and of the live windows" else " · " + w.why.joinToString("; "))
            w.tasks.forEach { id -> byId[id]?.let { add("   " + taskLine(it, rows[it.id.uppercase()])) } }
        }
        if (plan.unproven.isNotEmpty()) add("unproven (the text names no code, so it cannot be shown clear): ${plan.unproven.joinToString(", ")}")
    }.joinToString("\n")

    private fun taskLine(t: DispatchPlan.Task, row: TaskRow?): String {
        val keys = t.blocking.sorted().take(KEY_FILES).map { "file:$it" } + t.blocking.map { it.substringBeforeLast('/', "") }.filter { it.isNotEmpty() }.distinct().sorted().take(KEY_DIRS).map { "dir:$it/" }
        val more = (t.blocking.size - KEY_FILES).takeIf { it > 0 }?.let { " (+$it files)" }.orEmpty()
        val state = row?.state?.let { " $it" }.orEmpty() + row?.parent?.let { " ‹$it›" }.orEmpty()
        return "${t.id}${if (t.light) " light" else ""} ${t.priority ?: "-"}$state ${t.summary.take(SUMMARY)}  keys ${keys.joinToString(" ").ifEmpty { "-" }}$more"
    }

    private fun waiting(plan: DispatchPlan.Result, notes: List<DispatchPlan.Waiting>, rows: Map<String, TaskRow>, summary: List<String>): String =
        (listOf("## waiting") + (plan.waiting + notes).map { "${it.id}${rows[it.id.uppercase()]?.state?.let { s -> " ($s)" }.orEmpty()}  ${it.reason}" } + summary).joinToString("\n")

    private fun live(live: List<TaskCodeQuery.LiveWindow>, sets: List<DispatchPlan.Live>): String =
        (listOf("## live") + live.zip(sets).map { (w, s) -> "${s.label} on ${w.branch}: ${s.files.size} files " + s.files.sorted().take(LIVE_FILES).joinToString(", ") }).joinToString("\n")

    private companion object {
        const val DEFAULT_POOL = 15
        const val POOL_PER_SLOT = 4
        val PROJECT_TERM = Regex("(?:^|[\\s{(,])(?:project|in)\\s*:", RegexOption.IGNORE_CASE)
        const val LIGHT_FILES = 3
        const val LIGHT_TEXT = 2_000
        const val KEY_FILES = 4
        const val KEY_DIRS = 3
        const val SUMMARY = 70
        const val LIVE_FILES = 4
    }
}
