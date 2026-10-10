package codeloupe.commands

import codeloupe.ingest.TranscriptDb
import codeloupe.metrics.Usage

/** Reads the runs and steps of the transcript database for a [CommandReport]; the reader lock is held for one query at a time, never for a whole report. */
class CommandSource(private val db: TranscriptDb) {
    fun runs(filter: CommandFilter): List<CommandRun> = synchronized(db.reader) {
        val where = StringBuilder("start_ms >= ?")
        val args = arrayListOf<Any>(filter.fromMs)
        filter.toMs?.let { where.append(" AND start_ms <= ?"); args += it }
        filter.role?.let { where.append(" AND role = ?"); args += it }
        filter.run?.let { run ->
            where.append(" AND (id = ? OR file = ? OR session = ?)")
            args += run.toLongOrNull() ?: -1L
            args += run
            args += run
        }
        db.reader.prepareStatement(
            "SELECT id, role, file, ter, title, model, start_ms, turns, cost, input, cw5m, cw1h, cache_read, output FROM runs WHERE $where ORDER BY id",
        ).use { s ->
            args.forEachIndexed { i, a -> if (a is Long) s.setLong(i + 1, a) else s.setString(i + 1, a.toString()) }
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        add(CommandRun(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getLong(7), r.getInt(8), r.getLong(9), Usage(r.getLong(10), r.getLong(11), r.getLong(12), r.getLong(13), r.getLong(14))))
                    }
                }
            }
        }
    }

    fun steps(runId: Long): List<CommandStep> = synchronized(db.reader) {
        db.reader.prepareStatement("SELECT turn, name, category, summary, chars, err, in_chars, in_hash FROM steps WHERE run_id = ? ORDER BY seq").use { s ->
            s.setLong(1, runId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val inChars = r.getLong(7).takeIf { !r.wasNull() }
                        val inHash = r.getLong(8).takeIf { !r.wasNull() }
                        add(CommandStep(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5), r.getInt(6) == 1, inChars, inHash))
                    }
                }
            }
        }
    }
}
