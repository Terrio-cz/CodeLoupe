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
 * A hit is real when the title shares two of the draft's title words (one if the title has one), or when it shares at least
 * three words and two fifths of everything searched; a task that only happens to contain "write" and "updates" is not.
 */
object Similar {
    const val RECENT_DAYS = 90L
    private const val CANDIDATES = 60

    data class Hit(val row: TaskRow, val shared: List<String>)

    fun find(
        store: MirrorStore,
        summary: String,
        description: String = "",
        limit: Int = 5,
        nowMs: Long = System.currentTimeMillis(),
        /** Only this project's tasks (a mirror holds several), or null. */
        project: String? = null,
    ): List<Hit> {
        val terms = SimilarTerms.of(summary, description)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "\"$it\"" }
        val since = nowMs - Duration.ofDays(RECENT_DAYS).toMillis()
        val titleTerms = SimilarTerms.of(summary, "").toSet()
        val needTitle = minOf(2, titleTerms.size)
        val needAll = maxOf(3, Math.ceil(terms.size * 0.4).toInt())
        return store.read { db ->
            val ids = db.query(
                "SELECT i.id FROM issues_fts f JOIN issues i ON i.rowid = f.rowid WHERE issues_fts MATCH ? AND (i.resolved IS NULL OR i.resolved > ?) AND (? IS NULL OR i.project = ? COLLATE NOCASE) " +
                    "ORDER BY bm25(issues_fts, 10.0, 1.0, 0.4) LIMIT $CANDIDATES",
                match, since, project, project,
            ) { it.getString(1) }
            if (ids.isEmpty()) return@read emptyList()
            val rows = TaskRows.select(db, "i.id IN (${ids.joinToString(",") { "?" }})", "i.num", ids).associateBy { it.id.uppercase() }
            val json = db.query("SELECT id, json FROM issues WHERE id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray()) { it.getString(1).uppercase() to it.getString(2) }.toMap()
            ids.mapNotNull { id ->
                val row = rows[id.uppercase()] ?: return@mapNotNull null
                val issue = json[id.uppercase()]?.let { runCatching { JsonFormat.json.decodeFromString(TrackerIssue.serializer(), it) }.getOrNull() }
                val theirs = SimilarTerms.words(row.summary + " " + issue?.description.orEmpty()).toSet()
                Hit(row, terms.filter { it in theirs })
            }.filter { hit -> hit.shared.count { it in titleTerms } >= needTitle && titleTerms.isNotEmpty() || hit.shared.size >= needAll }
                .take(limit)
        }
    }

    fun render(hits: List<Hit>): String {
        if (hits.isEmpty()) return "no similar task (open or resolved in the last $RECENT_DAYS days): create the issue"
        val lines = hits.map { "${it.row.line(withParent = true)}  ≈ ${it.shared.take(6).joinToString(", ")}" }
        return (listOf("${hits.size} similar tasks (best first):") + lines +
            "Same work? Extend or comment on that task, or link the new one with `relates to`, instead of creating a duplicate.").joinToString("\n")
    }
}
