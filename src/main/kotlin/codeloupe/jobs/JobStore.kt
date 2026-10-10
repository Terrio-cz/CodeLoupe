package codeloupe.jobs

import codeloupe.JsonFormat
import codeloupe.platform.Sqlite
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * `<home>/jobs.db`: every job's record. A finished job stays for at least [RETAIN] (7 days) and for as long as it is among the newest
 * [keep]; older ones go with their logs. [HARD_CAP] bounds the table whatever the age. A record this version cannot read is skipped
 * in listings, never a failure of the whole list.
 */
class JobStore(file: Path, private val clock: Clock = Clock.systemUTC(), private val keep: Int = KEEP) : AutoCloseable {
    private val db: Connection = Sqlite.open(file, SCHEMA)
    private var writes = 0

    init {
        prune()
    }

    @Synchronized
    fun put(job: JobRecord) {
        db.prepareStatement(
            "INSERT INTO jobs(id, status, record) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET status = excluded.status, record = excluded.record",
        ).use { s ->
            s.setString(1, job.id)
            s.setString(2, job.status.label)
            s.setString(3, JsonFormat.json.encodeToString(JobRecord.serializer(), job))
            s.executeUpdate()
        }
        if (++writes % PRUNE_EVERY == 0) prune()
    }

    @Synchronized
    fun get(id: String): JobRecord? = db.prepareStatement("SELECT record FROM jobs WHERE id = ?").use { s ->
        s.setString(1, id)
        s.executeQuery().use { r -> if (r.next()) decode(r.getString(1)) else null }
    }

    @Synchronized
    fun recent(limit: Int): List<JobRecord> = db.prepareStatement("SELECT record FROM jobs ORDER BY rowid DESC LIMIT ?").use { s ->
        s.setInt(1, limit)
        s.executeQuery().use { r -> buildList { while (r.next()) decodeOrNull(r.getString(1))?.let(::add) } }
    }

    /** Jobs a previous daemon left queued or running. */
    @Synchronized
    fun unfinished(): List<JobRecord> = db.createStatement().use { s ->
        s.executeQuery("SELECT record FROM jobs WHERE status IN ('queued', 'running') ORDER BY rowid").use { r ->
            buildList { while (r.next()) decodeOrNull(r.getString(1))?.let(::add) }
        }
    }

    @Synchronized
    fun count(): Long = db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM jobs").use { it.next(); it.getLong(1) } }

    @Synchronized
    override fun close() = db.close()

    private class Row(val rowid: Long, val id: String, val record: JobRecord?)

    @Synchronized
    private fun prune() {
        val newest = db.createStatement().use { s -> s.executeQuery("SELECT MAX(rowid) FROM jobs").use { r -> r.next(); r.getLong(1).takeIf { !r.wasNull() } } } ?: return
        val cutoff = clock.instant().minus(RETAIN)
        val beyondKeep = db.prepareStatement("SELECT rowid, id, record FROM jobs WHERE rowid <= ? AND status NOT IN ('queued', 'running')").use { s ->
            s.setLong(1, newest - keep)
            s.executeQuery().use { r -> buildList { while (r.next()) add(Row(r.getLong(1), r.getString(2), decodeOrNull(r.getString(3)))) } }
        }
        for (row in beyondKeep) {
            val last = row.record?.let { it.endedAt ?: it.createdAt }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            // A record this version cannot read may be a newer one's: it stays until the cap.
            val expired = row.rowid <= newest - HARD_CAP || (row.record != null && (last == null || last.isBefore(cutoff)))
            if (!expired) continue
            row.record?.let { record -> runCatching { Files.deleteIfExists(Path.of(record.log)) } }
            db.prepareStatement("DELETE FROM jobs WHERE id = ?").use { it.setString(1, row.id); it.executeUpdate() }
        }
    }

    private fun decode(text: String) = JsonFormat.json.decodeFromString(JobRecord.serializer(), text)

    private fun decodeOrNull(text: String): JobRecord? = runCatching { decode(text) }.getOrNull()

    companion object {
        const val KEEP = 500
        const val HARD_CAP = 20_000
        const val PRUNE_EVERY = 100
        val RETAIN: Duration = Duration.ofDays(7)
        private val SCHEMA = listOf("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, status TEXT NOT NULL, record TEXT NOT NULL)")
    }
}
