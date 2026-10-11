package codeloupe.ingest

import java.sql.Connection
import java.sql.Types

/**
 * Keeps the `/usage` limits of a read in `usage_snapshots`. Unlike runs and steps this table is never cleared or rebuilt: Claude Code
 * deletes old transcripts and the same limits come again when a transcript is read again, so a row is written once and later reads only
 * fill an account or identity it did not have yet; only a read that knows the login replaces an account decided by location alone.
 */
internal object UsageSnapshotWriter {
    // A read that knows the login (the transcript names it, or the session was stamped) has resolved the account with it, so it replaces an account decided by location alone.
    private const val ACCOUNT_OF_READ = "CASE WHEN excluded.identity IS NOT NULL THEN coalesce(excluded.account, usage_snapshots.account) ELSE coalesce(usage_snapshots.account, excluded.account) END"

    fun store(c: Connection, d: FileDelta) {
        val path = d.found.key
        if (d.snapshots.isNotEmpty()) {
            c.prepareStatement(
                "INSERT INTO usage_snapshots(path, at_ms, kind, scope, grp, percent, resets_ms, severity, active, session, account, identity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(path, at_ms, kind, scope) DO UPDATE SET account = $ACCOUNT_OF_READ, identity = coalesce(usage_snapshots.identity, excluded.identity)",
            ).use { s ->
                for (u in d.snapshots) {
                    s.setString(1, path)
                    s.setLong(2, u.atMs)
                    s.setString(3, u.kind)
                    s.setString(4, u.scope)
                    s.setString(5, u.group)
                    u.percent?.let { s.setDouble(6, it) } ?: s.setNull(6, Types.REAL)
                    u.resetsAtMs?.let { s.setLong(7, it) } ?: s.setNull(7, Types.INTEGER)
                    s.setString(8, u.severity)
                    u.active?.let { s.setInt(9, if (it) 1 else 0) } ?: s.setNull(9, Types.INTEGER)
                    s.setString(10, d.found.session)
                    s.setString(11, d.account)
                    s.setString(12, d.identity)
                    s.addBatch()
                }
                s.executeBatch()
            }
        }
        // The account may be learned from a later part of the transcript than the report it belongs to.
        if (d.account != null || d.identity != null) {
            c.prepareStatement("UPDATE usage_snapshots SET account = CASE WHEN ?2 IS NOT NULL THEN coalesce(?1, account) ELSE coalesce(account, ?1) END, identity = coalesce(identity, ?2) WHERE path = ?3").use { s ->
                s.setString(1, d.account)
                s.setString(2, d.identity)
                s.setString(3, path)
                s.executeUpdate()
            }
        }
    }
}
