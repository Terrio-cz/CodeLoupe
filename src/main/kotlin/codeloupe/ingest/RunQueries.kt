package codeloupe.ingest

import codeloupe.metrics.Usage
import java.sql.PreparedStatement
import java.sql.ResultSet

/** The reads of the UI API on the ingested transcripts. Each answers from indexes in a few milliseconds, whatever the number of runs. */
class RunQueries(private val db: TranscriptDb) {
    fun runs(fromMs: Long, role: String?, text: String?, sort: RunSort, offset: Int, limit: Int): Page<RunRecord> = synchronized(db.reader) {
        val where = StringBuilder("start_ms >= ?")
        val args = arrayListOf<Any>(fromMs)
        if (role != null) {
            where.append(" AND role = ?")
            args += role
        }
        if (text != null) {
            where.append(" AND (title LIKE ? ESCAPE '\\' OR ter LIKE ? ESCAPE '\\' OR role LIKE ? ESCAPE '\\' OR file LIKE ? ESCAPE '\\')")
            repeat(4) { args += "%${text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%" }
        }
        val total = db.reader.prepareStatement("SELECT count(*) FROM runs WHERE $where").use { s -> bind(s, args).executeQuery().use { it.next(); it.getInt(1) } }
        val items = db.reader.prepareStatement("SELECT $RUN_COLUMNS FROM runs WHERE $where ORDER BY ${sort.column} DESC, id DESC LIMIT ? OFFSET ?").use { s ->
            bind(s, args + limit + offset).executeQuery().use { r -> buildList { while (r.next()) add(run(r)) } }
        }
        Page(items, total)
    }

    fun run(id: Long): RunRecord? = synchronized(db.reader) {
        db.reader.prepareStatement("SELECT $RUN_COLUMNS FROM runs WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeQuery().use { if (it.next()) run(it) else null }
        }
    }

    fun roles(): List<String> = synchronized(db.reader) {
        db.reader.createStatement().use { s -> s.executeQuery("SELECT DISTINCT role FROM runs ORDER BY role").use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
    }

    fun categories(runId: Long): List<CategoryCost> = synchronized(db.reader) {
        db.reader.prepareStatement(
            "SELECT s.category, count(*), sum(s.chars), sum(s.chars * max(0, r.turns - s.turn)), " +
                "sum(CAST(${attr("r.turns", "s")} + 0.5 AS INTEGER)), sum(s.err) FROM steps s JOIN runs r ON r.id = s.run_id WHERE s.run_id = ? " +
                "GROUP BY s.category ORDER BY 4 DESC",
        ).use { s ->
            s.setLong(1, runId)
            s.executeQuery().use { r -> buildList { while (r.next()) add(CategoryCost(r.getString(1), r.getInt(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getInt(6))) } }
        }
    }

    fun steps(runId: Long, sort: StepSort, offset: Int, limit: Int): Page<StepRecord> = synchronized(db.reader) {
        val total = db.reader.prepareStatement("SELECT tool_calls FROM runs WHERE id = ?").use { s -> s.setLong(1, runId); s.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }
        val items = db.reader.prepareStatement(
            "SELECT s.seq, s.turn, s.at_ms, s.name, s.category, s.summary, s.chars, s.ms, s.err, s.error, s.chars * max(0, r.turns - s.turn), " +
                "CAST(${attr("r.turns", "s")} + 0.5 AS INTEGER) AS attr, (SELECT g.kind FROM gaps g WHERE g.run_id = s.run_id AND g.seq = s.seq LIMIT 1) " +
                "FROM steps s JOIN runs r ON r.id = s.run_id WHERE s.run_id = ? ORDER BY ${sort.order} LIMIT ? OFFSET ?",
        ).use { s ->
            s.setLong(1, runId)
            s.setInt(2, limit)
            s.setInt(3, offset)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val at = r.getLong(3).takeIf { !r.wasNull() }
                        add(StepRecord(r.getInt(1), r.getInt(2), at, r.getString(4), r.getString(5), r.getString(6), r.getInt(7), r.getLong(8), r.getInt(9) == 1, r.getString(10), r.getLong(11), r.getLong(12), r.getString(13)))
                    }
                }
            }
        }
        Page(items, total)
    }

    /** Weighted tokens used per hour (epoch hour -> tokens) in the hours [fromHour, toHour). */
    fun hours(fromHour: Long, toHour: Long): Map<Long, Double> = synchronized(db.reader) {
        db.reader.prepareStatement("SELECT hour, sum(cost) FROM usage_hours WHERE hour >= ? AND hour < ? GROUP BY hour").use { s ->
            s.setLong(1, fromHour)
            s.setLong(2, toHour)
            s.executeQuery().use { r -> HashMap<Long, Double>().also { while (r.next()) it[r.getLong(1)] = r.getDouble(2) } }
        }
    }

    fun gaps(fromMs: Long, tool: String?, kinds: List<String>, limit: Int): List<GapRecord> = synchronized(db.reader) {
        val where = StringBuilder("g.at_ms >= ? AND g.kind != 'busy'")
        val args = arrayListOf<Any>(fromMs)
        if (tool != null) {
            where.append(" AND g.tool = ?")
            args += tool
        }
        if (kinds.isNotEmpty()) {
            where.append(" AND g.kind IN (${kinds.joinToString { "?" }})")
            args.addAll(kinds)
        }
        db.reader.prepareStatement(
            "SELECT g.id, g.run_id, r.file, g.seq, g.turn, g.at_ms, g.tool, g.shape, g.kind, g.token, g.fallback FROM gaps g JOIN runs r ON r.id = g.run_id " +
                "WHERE $where ORDER BY g.at_ms DESC, g.id DESC LIMIT ?",
        ).use { s ->
            bind(s, args + limit).executeQuery().use { r ->
                buildList { while (r.next()) add(GapRecord(r.getLong(1), r.getLong(2), r.getString(3), r.getInt(4), r.getInt(5), r.getLong(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11))) }
            }
        }
    }

    fun gapGroups(fromMs: Long, tool: String?, kinds: List<String>): List<GapGroup> = synchronized(db.reader) {
        val where = StringBuilder("at_ms >= ? AND kind != 'busy'")
        val args = arrayListOf<Any>(fromMs)
        if (tool != null) {
            where.append(" AND tool = ?")
            args += tool
        }
        if (kinds.isNotEmpty()) {
            where.append(" AND kind IN (${kinds.joinToString { "?" }})")
            args.addAll(kinds)
        }
        db.reader.prepareStatement("SELECT tool, shape, coalesce(fallback, 'other'), count(*), max(at_ms) FROM gaps WHERE $where GROUP BY tool, shape, coalesce(fallback, 'other') ORDER BY 4 DESC").use { s ->
            bind(s, args).executeQuery().use { r -> buildList { while (r.next()) add(GapGroup(r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getLong(5))) } }
        }
    }

    fun gapCount(fromMs: Long): Int = synchronized(db.reader) {
        db.reader.prepareStatement("SELECT count(*) FROM gaps WHERE at_ms >= ? AND kind != 'busy'").use { s -> s.setLong(1, fromMs); s.executeQuery().use { it.next(); it.getInt(1) } }
    }

    private fun bind(s: PreparedStatement, args: List<Any>): PreparedStatement {
        args.forEachIndexed { i, a -> if (a is Long) s.setLong(i + 1, a) else if (a is Int) s.setInt(i + 1, a) else s.setString(i + 1, a.toString()) }
        return s
    }

    private fun run(r: ResultSet) = RunRecord(
        id = r.getLong(1), file = r.getString(2), session = r.getString(3), project = r.getString(4), kind = r.getString(5), role = r.getString(6),
        ter = r.getString(7), model = r.getString(8), title = r.getString(9), startMs = r.getLong(10), endMs = r.getLong(11), durationSec = r.getLong(12),
        turns = r.getInt(13), usage = Usage(r.getLong(14), r.getLong(15), r.getLong(16), r.getLong(17), r.getLong(18)), weighted = r.getLong(19),
        peakContext = r.getLong(20), toolCalls = r.getInt(21), toolErrors = r.getInt(22), toolSec = Math.round(r.getLong(23) / 1000.0), share = r.getInt(24),
    )

    private fun attr(turns: String, step: String) = "$step.chars / 4.0 * (2.0 + 0.1 * max(0, $turns - $step.turn))"

    private companion object {
        const val RUN_COLUMNS = "id, file, session, project, kind, role, ter, model, title, start_ms, end_ms, duration_s, turns, input, cw5m, cw1h, cache_read, output, cost, peak, " +
            "tool_calls, tool_errors, tool_ms, share"
    }
}
