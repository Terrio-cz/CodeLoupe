package codeloupe.tracker.read

import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.mirror.query
import java.sql.Connection

/** Loads [TaskRow]s straight from the mirror's tables (no issue JSON), by filter or by id. */
object TaskRows {
    /** Columns of a task row over `issues i`. */
    private val SELECT = """SELECT i.id, i.summary, i.state, i.resolved, i.parent, i.updated, i.type, i.priority, i.priority_rank,
  (SELECT group_concat(l.other || CASE WHEN o.id IS NULL THEN '?' ELSE '' END, ',') FROM links l LEFT JOIN issues o ON o.id = l.other
     WHERE l.issue = i.id AND l.kind = 'DEPENDS_ON' AND o.resolved IS NULL)
FROM issues i"""

    fun select(db: Connection, where: String, order: String, args: List<Any?>): List<TaskRow> =
        db.query("$SELECT WHERE $where ORDER BY $order", *args.toTypedArray()) { rs ->
            TaskRow(
                id = rs.getString(1), summary = rs.getString(2), state = rs.getString(3), resolved = rs.getObject(4) != null,
                parent = rs.getString(5), updated = rs.getLong(6), type = rs.getString(7), priority = rs.getString(8),
                priorityRank = rs.getInt(9).takeUnless { rs.wasNull() }, blockers = rs.getString(10)?.split(',').orEmpty(),
            )
        }

    fun byIds(store: MirrorStore, ids: Collection<String>): Map<String, TaskRow> {
        if (ids.isEmpty()) return emptyMap()
        return store.read { db ->
            ids.chunked(500).flatMap { chunk -> select(db, "i.id IN (${chunk.joinToString(",") { "?" }})", "i.num", chunk) }
        }.associateBy { it.id.uppercase() }
    }

    fun row(store: MirrorStore, id: String): TaskRow? = byIds(store, listOf(id)).values.firstOrNull()
}
