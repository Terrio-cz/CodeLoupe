package codeloupe.taskcode

/**
 * Which tasks get a window now so that no two windows fight over code: tasks whose predicted touch sets overlap (one file,
 * or with [Width.DIR] one directory) or that depend on each other form a group that is never split; a group of up to three
 * light tasks is one batch, any other group a chain of at most three steps with dependencies first; a group that
 * overlaps a live worktree waits. Windows are ranked by their most urgent task and cut at [slots]. Markdown files never block.
 */
class DispatchPlan(private val width: Width = Width.DIR) {
    enum class Width { FILE, DIR }

    /** [files]: path → how sure the prediction is (`=` sure, `~` likely, `?` guess, `+` new file). [blockers]: unresolved dependencies, `?` marks one the mirror lacks. */
    class Task(val id: String, val summary: String, val priority: String?, val rank: Int?, val light: Boolean, val blockers: Set<String>, val files: Map<String, Char>) {
        val blocking: Set<String> get() = files.keys.filterNot { it.endsWith(".md") }.toSet()
    }

    /** A window already working: a worktree and the files it changed (and its task predicted to). */
    class Live(val label: String, val files: Set<String>)

    class Window(val steps: List<List<String>>, val why: List<String>) {
        val tasks: List<String> get() = steps.flatten()
        val kind: String get() = if (steps.size == 1 && steps.single().size > 1) "batch" else if (steps.size > 1) "chain" else "single"
    }

    class Waiting(val id: String, val reason: String)

    class Result(val windows: List<Window>, val waiting: List<Waiting>, val unproven: List<String>)

    fun plan(tasks: List<Task>, live: List<Live>, slots: Int): Result {
        val waiting = ArrayList<Waiting>()
        var open = tasks
        // A dependency that is neither done nor a candidate leaves the task waiting; so does whatever depends on such a task.
        while (true) {
            val ids = open.map { it.id.uppercase() }.toSet()
            val unmet = open.filter { t -> t.blockers.any { it.uppercase().removeSuffix("?") !in ids || it.endsWith("?") } }
            if (unmet.isEmpty()) break
            for (t in unmet) waiting += Waiting(t.id, "depends on " + t.blockers.filter { it.uppercase().removeSuffix("?") !in ids || it.endsWith("?") }.joinToString(", ") { it.removeSuffix("?") } + " (not done, not a candidate)")
            open = open - unmet.toSet()
        }
        val groups = groups(open)
        val windows = ArrayList<Pair<List<Task>, Window>>()
        for (group in groups) {
            val fight = group.firstNotNullOfOrNull { t -> live.firstNotNullOfOrNull { l -> clash(t.blocking, l.files)?.let { "${t.id} fights with live ${l.label}: $it" } } }
            if (fight != null) {
                group.forEach { waiting += Waiting(it.id, if (group.size == 1) fight else "$fight (grouped: ${group.joinToString("+") { g -> g.id }})") }
                continue
            }
            windows += group to window(group, waiting)
        }
        val ranked = windows.sortedWith(compareBy({ w -> w.first.minOf { it.rank ?: Int.MAX_VALUE } }, { w -> w.first.minOf { number(it.id) } }))
        val kept = ranked.take(slots.coerceAtLeast(0))
        ranked.drop(kept.size).forEach { (_, w) -> w.tasks.forEach { waiting += Waiting(it, "no free window (slots $slots)") } }
        val result = kept.map { it.second }
        val unproven = kept.flatMap { it.first }.filter { it.blocking.isEmpty() }.map { it.id }
        return Result(result, waiting.sortedBy { number(it.id) }, unproven)
    }

    /** Connected groups of tasks that clash or depend on each other, each in dependency order, then most urgent first. */
    private fun groups(tasks: List<Task>): List<List<Task>> {
        val parent = tasks.indices.associateWith { it }.toMutableMap()
        fun find(i: Int): Int = if (parent.getValue(i) == i) i else find(parent.getValue(i)).also { parent[i] = it }
        for (i in tasks.indices) for (j in i + 1 until tasks.size) if (related(tasks[i], tasks[j]) != null) parent[find(i)] = find(j)
        return tasks.indices.groupBy { find(it) }.values.map { ordered(it.map(tasks::get)) }
    }

    private fun ordered(group: List<Task>): List<Task> {
        val left = group.sortedWith(compareBy({ it.rank ?: Int.MAX_VALUE }, { number(it.id) })).toMutableList()
        val done = ArrayList<Task>()
        while (left.isNotEmpty()) {
            val next = left.firstOrNull { t -> t.blockers.none { b -> left.any { it.id.equals(b.removeSuffix("?"), ignoreCase = true) } } } ?: left.first()
            left -= next
            done += next
        }
        return done
    }

    private fun window(group: List<Task>, waiting: MutableList<Waiting>): Window {
        val why = group.indices.flatMap { i -> (i + 1 until group.size).mapNotNull { j -> related(group[i], group[j]) } }.distinct().take(MAX_WHY)
        val dependent = group.any { t -> t.blockers.any { b -> group.any { it.id.equals(b.removeSuffix("?"), ignoreCase = true) } } }
        val steps = when {
            group.size == 1 -> listOf(listOf(group.single().id))
            group.size <= MAX_BATCH && group.all { it.light } && !dependent -> listOf(group.map { it.id })
            else -> group.map { listOf(it.id) }.also { chain -> chain.drop(MAX_STEPS).forEach { waiting += Waiting(it.single(), "after the chain ${chain.take(MAX_STEPS).joinToString("→") { s -> s.single() }}") } }.take(MAX_STEPS)
        }
        return Window(steps, why)
    }

    /** Why two tasks must share a window, or null when they are free of each other. */
    private fun related(a: Task, b: Task): String? {
        if (a.blockers.any { it.removeSuffix("?").equals(b.id, ignoreCase = true) }) return "${a.id} depends on ${b.id}"
        if (b.blockers.any { it.removeSuffix("?").equals(a.id, ignoreCase = true) }) return "${b.id} depends on ${a.id}"
        return clash(a.blocking, b.blocking)?.let { "$it (${a.id} ${mark(a, it)}, ${b.id} ${mark(b, it)})" }
    }

    private fun mark(t: Task, evidence: String): Char = t.files[evidence] ?: t.files.entries.firstOrNull { dir(it.key) == evidence }?.value ?: '?'

    /** The shared file, or with [Width.DIR] the shared directory, of two path sets; null when there is none. */
    private fun clash(a: Set<String>, b: Set<String>): String? {
        a.firstOrNull { it in b }?.let { return it }
        if (width != Width.DIR) return null
        val dirs = a.map(::dir).filter { it.isNotEmpty() }.toSet()
        return b.map(::dir).firstOrNull { it in dirs }
    }

    private fun dir(path: String) = path.substringBeforeLast('/', "")

    private fun number(id: String) = id.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE

    private companion object {
        const val MAX_BATCH = 3
        const val MAX_STEPS = 3
        const val MAX_WHY = 2
    }
}
