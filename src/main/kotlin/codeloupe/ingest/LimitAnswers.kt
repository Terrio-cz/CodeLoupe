package codeloupe.ingest

import codeloupe.platform.IsoTime
import java.time.Instant

/** Reads the usage-limit answers runs got, with the account of the transcript each run lies in. */
class LimitAnswers(private val db: TranscriptDb) {
    /** The answers in `[fromMs, toMs]` oldest first, at most [limit], cut at the newest. */
    fun between(fromMs: Long, toMs: Long, who: LimitSnapshots.Who, limit: Int): List<LimitAnswer> = synchronized(db.reader) {
        val (filter, arg) = when (who) {
            LimitSnapshots.Who.All -> "" to null
            LimitSnapshots.Who.Unknown -> "AND f.account IS NULL" to null
            is LimitSnapshots.Who.Account -> "AND f.account = ?" to who.id
        }
        db.reader.prepareStatement(
            "SELECT * FROM (SELECT l.at_ms, l.kind, l.resets_ms, r.session, f.account FROM limits l JOIN runs r ON r.id = l.run_id LEFT JOIN files f ON f.path = r.path " +
                "WHERE l.at_ms >= ? AND l.at_ms <= ? $filter ORDER BY l.at_ms DESC LIMIT ?) ORDER BY at_ms",
        ).use { s ->
            var i = 0
            s.setLong(++i, fromMs)
            s.setLong(++i, toMs)
            if (arg != null) s.setString(++i, arg)
            s.setInt(++i, limit)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val resets = r.getLong(3).takeIf { !r.wasNull() }
                        add(LimitAnswer(IsoTime.of(Instant.ofEpochMilli(r.getLong(1))), r.getString(2), resets?.let { IsoTime.of(Instant.ofEpochMilli(it)) }, r.getString(4), r.getString(5)))
                    }
                }
            }
        }
    }

    companion object {
        fun csv(rows: List<LimitAnswer>): String = buildString {
            append("at,kind,resetsAt,session,account\n")
            for (r in rows) append(listOf(r.at, r.kind, r.resetsAt, r.session, r.account).joinToString(",") { LimitCsv.cell(it) }).append('\n')
        }
    }
}
