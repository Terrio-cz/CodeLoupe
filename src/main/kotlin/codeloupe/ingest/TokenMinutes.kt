package codeloupe.ingest

import codeloupe.platform.IsoTime
import java.time.Instant

/** Reads the stored token components per minute and model ([RunWriter]), with the account of the transcript each run lies in. */
class TokenMinutes(private val db: TranscriptDb) {
    /** The minutes in `[fromMs, toMs]` oldest first, at most [limit]; the page is cut at the newest, as [LimitSnapshots.between] does. */
    fun between(fromMs: Long, toMs: Long, who: LimitSnapshots.Who, limit: Int): List<TokenMinute> = synchronized(db.reader) {
        val (filter, arg) = when (who) {
            LimitSnapshots.Who.All -> "" to null
            LimitSnapshots.Who.Unknown -> "AND f.account IS NULL" to null
            is LimitSnapshots.Who.Account -> "AND f.account = ?" to who.id
        }
        db.reader.prepareStatement(
            "SELECT * FROM (SELECT m.minute, r.session, r.kind, r.role, r.project, m.model, m.input, m.cw5m, m.cw1h, m.cache_read, m.output, m.thinking, f.account, f.identity " +
                "FROM usage_minutes m JOIN runs r ON r.id = m.run_id LEFT JOIN files f ON f.path = r.path " +
                "WHERE m.minute >= ? AND m.minute <= ? $filter ORDER BY m.minute DESC, m.run_id DESC, m.model DESC LIMIT ?) ORDER BY minute, session, model",
        ).use { s ->
            var i = 0
            s.setLong(++i, Math.floorDiv(fromMs, RunWriter.MINUTE_MS))
            s.setLong(++i, Math.floorDiv(toMs, RunWriter.MINUTE_MS))
            if (arg != null) s.setString(++i, arg)
            s.setInt(++i, limit)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            TokenMinute(
                                IsoTime.of(Instant.ofEpochMilli(r.getLong(1) * RunWriter.MINUTE_MS)), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                                r.getLong(7), r.getLong(8), r.getLong(9), r.getLong(10), r.getLong(11), r.getLong(12), r.getString(13), r.getString(14),
                            ),
                        )
                    }
                }
            }
        }
    }
}
