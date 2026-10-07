package codeloupe.tracker.read

import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.mirror.query

/** `epic_progress(epic)`: counts by state, checked criteria, then the open tasks one line each with their blockers. */
object EpicProgress {
    private const val LISTED = 8

    fun render(store: MirrorStore, epicId: String, limit: Int): String {
        val epic = TaskRows.row(store, epicId) ?: return "no issue $epicId"
        val rows = TaskList.matching(store, TaskFilter.parse("epic: ${epic.id} sort: id"))
        if (rows.isEmpty()) return "${epic.line()}\nno subtasks"
        val resolved = rows.count { it.resolved }
        val states = rows.groupingBy { it.state ?: if (it.resolved) "resolved" else "open" }.eachCount().entries.sortedByDescending { it.value }
        val open = rows.filterNot { it.resolved }
        return buildList {
            add(epic.line())
            add("${rows.size} tasks, $resolved resolved (${resolved * 100 / rows.size} %): " + states.joinToString(" · ") { "${it.key} ${it.value}" })
            criteria(store, rows.map { it.id })?.let(::add)
            val blocked = open.count { it.blockers.isNotEmpty() }
            if (open.isNotEmpty()) add(TaskList.render(open, limit, "open" + if (blocked > 0) " ($blocked blocked ⛔)" else "", withParent = false))
        }.joinToString("\n")
    }

    /** `criteria 28/40 checked; open in TER-168 (3), TER-170 (2)`; null when no task has criteria. */
    private fun criteria(store: MirrorStore, ids: List<String>): String? {
        val counts = store.read { db ->
            ids.chunked(500).flatMap { chunk ->
                db.query("SELECT issue, sum(done), count(*) FROM criteria WHERE issue IN (${chunk.joinToString(",") { "?" }}) GROUP BY issue", *chunk.toTypedArray()) {
                    Triple(it.getString(1), it.getInt(2), it.getInt(3))
                }
            }
        }
        if (counts.isEmpty()) return null
        val withOpen = counts.filter { it.second < it.third }.sortedByDescending { it.third - it.second }
        val head = "criteria ${counts.sumOf { it.second }}/${counts.sumOf { it.third }} checked"
        if (withOpen.isEmpty()) return head
        return "$head; open in " + withOpen.take(LISTED).joinToString(", ") { "${it.first} (${it.third - it.second})" } + if (withOpen.size > LISTED) ", …" else ""
    }
}
