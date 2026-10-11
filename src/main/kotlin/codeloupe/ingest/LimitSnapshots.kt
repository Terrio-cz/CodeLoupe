package codeloupe.ingest

import codeloupe.platform.IsoTime
import java.sql.ResultSet
import java.time.Instant

/** Reads the stored `/usage` limits ([UsageSnapshotWriter]); no secret and no conversation text is in them. */
class LimitSnapshots(private val db: TranscriptDb) {
    /** Which account to list: all of them, one by id, or the snapshots whose account is not decidable. */
    sealed interface Who {
        data object All : Who
        data object Unknown : Who
        data class Account(val id: String) : Who
    }

    /** The snapshots written in `[fromMs, toMs]` oldest first, at most [limit]; the page is cut at the newest, so a cut list still ends at what is current. */
    fun between(fromMs: Long, toMs: Long, who: Who, limit: Int): List<LimitSnapshot> = synchronized(db.reader) {
        val (filter, arg) = filter(who)
        db.reader.prepareStatement(
            "SELECT * FROM (SELECT $COLUMNS FROM usage_snapshots WHERE at_ms >= ? AND at_ms <= ? $filter ORDER BY at_ms DESC, kind DESC, scope DESC LIMIT ?) ORDER BY at_ms, kind, scope",
        ).use { s ->
            var i = 0
            s.setLong(++i, fromMs)
            s.setLong(++i, toMs)
            if (arg != null) s.setString(++i, arg)
            s.setInt(++i, limit)
            s.executeQuery().use(::rows)
        }
    }

    /** The newest snapshot of every meter of every account: the state of each limit as last seen. */
    fun latest(who: Who): List<LimitSnapshot> = synchronized(db.reader) {
        val (filter, arg) = filter(who)
        db.reader.prepareStatement(
            "SELECT $COLUMNS FROM usage_snapshots s WHERE at_ms = (SELECT max(t.at_ms) FROM usage_snapshots t WHERE t.account IS s.account AND t.kind = s.kind AND t.scope = s.scope) $filter ORDER BY account, kind, scope",
        ).use { s ->
            if (arg != null) s.setString(1, arg)
            s.executeQuery().use(::rows).distinctBy { listOf(it.account, it.kind, it.scope) }
        }
    }

    private fun filter(who: Who): Pair<String, String?> = when (who) {
        Who.All -> "" to null
        Who.Unknown -> "AND account IS NULL" to null
        is Who.Account -> "AND account = ?" to who.id
    }

    private fun rows(r: ResultSet): List<LimitSnapshot> = buildList {
        while (r.next()) {
            add(
                LimitSnapshot(
                    at = IsoTime.of(Instant.ofEpochMilli(r.getLong(1))), kind = r.getString(2), group = r.getString(3), scope = r.getString(4).takeIf { it.isNotEmpty() },
                    percent = r.getDouble(5).takeIf { !r.wasNull() }, resetsAt = r.getLong(6).takeIf { !r.wasNull() }?.let { IsoTime.of(Instant.ofEpochMilli(it)) },
                    severity = r.getString(7), active = r.getInt(8).takeIf { !r.wasNull() }?.let { it == 1 }, session = r.getString(9), account = r.getString(10), identity = r.getString(11),
                ),
            )
        }
    }

    private companion object {
        const val COLUMNS = "at_ms, kind, grp, scope, percent, resets_ms, severity, active, session, account, identity"
    }
}
