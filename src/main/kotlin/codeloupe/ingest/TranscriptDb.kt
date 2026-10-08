package codeloupe.ingest

import codeloupe.platform.Sqlite
import java.nio.file.Path
import java.sql.Connection

/**
 * `<home>/transcripts.db`: what the daemon read of the agents' transcripts. [writer] belongs to the ingest and [reader]
 * to the UI API calls, so a long ingest transaction never blocks a screen (WAL). Both open on first use: a daemon nobody
 * asks about runs and costs nothing.
 */
class TranscriptDb(private val file: Path) : AutoCloseable {
    private val writerConnection = lazy { open() }

    // Opened after the writer, which creates the schema.
    private val readerConnection = lazy { writerConnection.value.let { open() } }

    val writer: Connection get() = writerConnection.value
    val reader: Connection get() = readerConnection.value

    private var depth = 0

    /**
     * [body] as one transaction on the writer, rolled back when it throws. Inside another transaction it is a savepoint: only its
     * own changes are undone, so a batch of files commits together and one bad file does not take the others with it.
     */
    fun <T> transaction(body: (Connection) -> T): T {
        val db = writer
        if (depth == 0) db.autoCommit = false
        val savepoint = if (depth > 0) db.setSavepoint() else null
        depth++
        try {
            val result = body(db)
            if (savepoint != null) db.releaseSavepoint(savepoint) else db.commit()
            return result
        } catch (e: Throwable) {
            if (savepoint != null) db.rollback(savepoint) else db.rollback()
            throw e
        } finally {
            if (--depth == 0) db.autoCommit = true
        }
    }

    fun meta(key: String): String? = reader.prepareStatement("SELECT value FROM meta WHERE key = ?").use { s ->
        s.setString(1, key)
        s.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    fun setMeta(db: Connection, key: String, value: String) {
        db.prepareStatement("INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use { s ->
            s.setString(1, key)
            s.setString(2, value)
            s.executeUpdate()
        }
    }

    /** Drops everything read so far, for transcripts that must be read again (other categories). */
    fun clear() = transaction { db ->
        db.createStatement().use { s -> listOf("steps", "usage_hours", "gaps", "runs", "files").forEach { s.executeUpdate("DELETE FROM $it") } }
    }

    override fun close() {
        if (readerConnection.isInitialized()) runCatching { reader.close() }
        if (writerConnection.isInitialized()) runCatching { writer.close() }
    }

    private fun open(): Connection = Sqlite.open(file, SCHEMA).also { db ->
        db.createStatement().use { it.execute("PRAGMA cache_size = -2048") }
    }

    private companion object {
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            // offset: bytes of the transcript already read; state: the parser snapshot to continue from.
            "CREATE TABLE IF NOT EXISTS files (path TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, offset INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, project TEXT NOT NULL, session TEXT NOT NULL, ter TEXT, state TEXT)",
            "CREATE TABLE IF NOT EXISTS runs (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL UNIQUE, file TEXT NOT NULL, session TEXT NOT NULL, " +
                "project TEXT NOT NULL, kind TEXT NOT NULL, role TEXT NOT NULL, ter TEXT, model TEXT, title TEXT NOT NULL, start_ms INTEGER NOT NULL, " +
                "end_ms INTEGER NOT NULL, duration_s INTEGER NOT NULL, turns INTEGER NOT NULL, input INTEGER NOT NULL, cw5m INTEGER NOT NULL, " +
                "cw1h INTEGER NOT NULL, cache_read INTEGER NOT NULL, output INTEGER NOT NULL, cost INTEGER NOT NULL, peak INTEGER NOT NULL, " +
                "tool_calls INTEGER NOT NULL DEFAULT 0, tool_errors INTEGER NOT NULL DEFAULT 0, tool_ms INTEGER NOT NULL DEFAULT 0, " +
                "result_attr INTEGER NOT NULL DEFAULT 0, share INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS runs_start ON runs(start_ms)",
            "CREATE INDEX IF NOT EXISTS runs_cost ON runs(cost)",
            "CREATE INDEX IF NOT EXISTS runs_turns ON runs(turns)",
            "CREATE INDEX IF NOT EXISTS runs_peak ON runs(peak)",
            "CREATE INDEX IF NOT EXISTS runs_share ON runs(share)",
            "CREATE INDEX IF NOT EXISTS runs_duration ON runs(duration_s)",
            "CREATE INDEX IF NOT EXISTS runs_role ON runs(role, start_ms)",
            // input and head are kept for the calls the gap detector reads; summary and error are redacted.
            "CREATE TABLE IF NOT EXISTS steps (run_id INTEGER NOT NULL, seq INTEGER NOT NULL, turn INTEGER NOT NULL, at_ms INTEGER, name TEXT NOT NULL, " +
                "category TEXT NOT NULL, summary TEXT NOT NULL, chars INTEGER NOT NULL, ms INTEGER NOT NULL, err INTEGER NOT NULL, error TEXT, " +
                "input TEXT, head TEXT, PRIMARY KEY (run_id, seq)) WITHOUT ROWID",
            "CREATE INDEX IF NOT EXISTS steps_chars ON steps(run_id, chars)",
            "CREATE INDEX IF NOT EXISTS steps_codeloupe ON steps(run_id) WHERE category = 'codeloupe'",
            "CREATE TABLE IF NOT EXISTS usage_hours (run_id INTEGER NOT NULL, hour INTEGER NOT NULL, cost REAL NOT NULL, PRIMARY KEY (run_id, hour)) WITHOUT ROWID",
            "CREATE INDEX IF NOT EXISTS usage_hours_hour ON usage_hours(hour)",
            "CREATE TABLE IF NOT EXISTS gaps (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, seq INTEGER NOT NULL, turn INTEGER NOT NULL, " +
                "at_ms INTEGER NOT NULL, week TEXT NOT NULL, tool TEXT NOT NULL, shape TEXT NOT NULL, kind TEXT NOT NULL, token TEXT, fallback TEXT)",
            "CREATE INDEX IF NOT EXISTS gaps_at ON gaps(at_ms)",
            "CREATE INDEX IF NOT EXISTS gaps_run ON gaps(run_id)",
            // One row per day or run that went over a budget: the event for it is sent once.
            "CREATE TABLE IF NOT EXISTS breaches (key TEXT PRIMARY KEY, at TEXT NOT NULL)",
        )
    }
}
