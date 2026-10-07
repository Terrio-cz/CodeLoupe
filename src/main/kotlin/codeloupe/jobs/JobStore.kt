package codeloupe.jobs

import codeloupe.JsonFormat
import codeloupe.platform.Sqlite
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** `<home>/jobs.db`: every job's record. Keeps the newest [KEEP] jobs; older ones go with their logs. */
class JobStore(file: Path) : AutoCloseable {
    private val db: Connection = Sqlite.open(file, SCHEMA)
    private var writes = 0

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
        s.executeQuery().use { r -> buildList { while (r.next()) add(decode(r.getString(1))) } }
    }

    /** Jobs a previous daemon left queued or running. */
    @Synchronized
    fun unfinished(): List<JobRecord> = db.createStatement().use { s ->
        s.executeQuery("SELECT record FROM jobs WHERE status IN ('queued', 'running') ORDER BY rowid").use { r ->
            buildList { while (r.next()) add(decode(r.getString(1))) }
        }
    }

    @Synchronized
    override fun close() = db.close()

    private fun prune() {
        val old = db.createStatement().use { s ->
            s.executeQuery("SELECT id, record FROM jobs WHERE rowid <= (SELECT MAX(rowid) FROM jobs) - $KEEP AND status NOT IN ('queued', 'running')").use { r ->
                buildList { while (r.next()) add(r.getString(1) to decode(r.getString(2)).log) }
            }
        }
        for ((id, log) in old) {
            runCatching { Files.deleteIfExists(Path.of(log)) }
            db.prepareStatement("DELETE FROM jobs WHERE id = ?").use { it.setString(1, id); it.executeUpdate() }
        }
    }

    private fun decode(text: String) = JsonFormat.json.decodeFromString(JobRecord.serializer(), text)

    private companion object {
        const val KEEP = 500
        const val PRUNE_EVERY = 100
        val SCHEMA = listOf("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, status TEXT NOT NULL, record TEXT NOT NULL)")
    }
}
