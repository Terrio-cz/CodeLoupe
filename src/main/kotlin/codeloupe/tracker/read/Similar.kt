package codeloupe.tracker.read

import codeloupe.JsonFormat
import codeloupe.tracker.TrackerIssue
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.mirror.query
import java.time.Duration

/**
 * `similar(summary, description)`: the tasks of the mirror that talk about the same thing as a draft issue, so an agent
 * extends or links one instead of creating a duplicate. Full-text ranking (bm25, the summary weighs most) over the
 * open tasks and those resolved in the last [RECENT_DAYS] days, then the words each hit really shares with the draft.
 * A hit needs two shared words (one if the draft has just one); the draft's own words are the only thing searched.
 */
object Similar {
    const val RECENT_DAYS = 90L
    private const val CANDIDATES = 60

    data class Hit(val row: TaskRow, val shared: List<String>)

    fun find(store: MirrorStore, summary: String, description: String = "", limit: Int = 5, nowMs: Long = System.currentTimeMillis()): List<Hit> {
        val terms = SimilarTerms.of(summary, description)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "\"$it\"" }
        val since = nowMs - Duration.ofDays(RECENT_DAYS).toMillis()
        val need = minOf(2, terms.size)
        return store.read { db ->
            val ids = db.query(
                "SELECT i.id FROM issues_fts f JOIN issues i ON i.rowid = f.rowid WHERE issues_fts MATCH ? AND (i.resolved IS NULL OR i.resolved > ?) " +
                    "ORDER BY bm25(issues_fts, 10.0, 1.0, 0.4) LIMIT $CANDIDATES",
                match, since,
            ) { it.getString(1) }
            if (ids.isEmpty()) return@read emptyList()
            val rows = TaskRows.select(db, "i.id IN (${ids.joinToString(",") { "?" }})", "i.num", ids).associateBy { it.id.uppercase() }
            val json = db.query("SELECT id, json FROM issues WHERE id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray()) { it.getString(1).uppercase() to it.getString(2) }.toMap()
            ids.mapNotNull { id ->
                val row = rows[id.uppercase()] ?: return@mapNotNull null
                val issue = json[id.uppercase()]?.let { runCatching { JsonFormat.json.decodeFromString(TrackerIssue.serializer(), it) }.getOrNull() }
                val theirs = SimilarTerms.words(row.summary + " " + issue?.description.orEmpty()).toSet()
                Hit(row, terms.filter { it in theirs })
            }.filter { it.shared.size >= need }.take(limit)
        }
    }

    fun render(hits: List<Hit>): String {
        if (hits.isEmpty()) return "no similar task (open or resolved in the last $RECENT_DAYS days): create the issue"
        val lines = hits.map { "${it.row.line(withParent = true)}  ≈ ${it.shared.take(6).joinToString(", ")}" }
        return (listOf("${hits.size} similar tasks (best first):") + lines +
            "Same work? Extend or comment on that task, or link the new one with `relates to`, instead of creating a duplicate.").joinToString("\n")
    }
}
