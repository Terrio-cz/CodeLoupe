package codeloupe.tracker.read

import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.mirror.query

/** `tasks(query)`: matching tasks, one line each, best full-text match first when there is text. */
object TaskList {
    fun matching(store: MirrorStore, filter: TaskFilter, extra: String = "1 = 1"): List<TaskRow> = store.read { db ->
        val ranked = filter.text?.let { text ->
            db.query(
                "SELECT i.id FROM issues_fts f JOIN issues i ON i.rowid = f.rowid WHERE issues_fts MATCH ? ORDER BY f.rank LIMIT $MAX_TEXT_HITS", text,
            ) { it.getString(1).uppercase() }
        }
        if (ranked != null && ranked.isEmpty()) return@read emptyList()
        val where = listOfNotNull(filter.where, extra, ranked?.let { ids -> "i.id IN (${ids.joinToString(",") { "?" }})" }).joinToString(" AND ")
        val rows = TaskRows.select(db, where, filter.order, filter.args + ranked.orEmpty())
        if (ranked == null || filter.order != TaskFilter.DEFAULT_ORDER) rows else ranked.withIndex().associate { it.value to it.index }.let { pos -> rows.sortedBy { pos[it.id.uppercase()] } }
    }

    fun render(rows: List<TaskRow>, limit: Int, what: String = "tasks", withParent: Boolean = true): String {
        if (rows.isEmpty()) return "no $what"
        val head = if (rows.size > limit) "${rows.size} $what, first $limit:" else "${rows.size} $what:"
        return (listOf(head) + rows.take(limit).map { it.line(withParent) }).joinToString("\n")
    }

    private const val MAX_TEXT_HITS = 2000
}
