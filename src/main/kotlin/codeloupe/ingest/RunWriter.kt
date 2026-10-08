package codeloupe.ingest

import codeloupe.JsonFormat
import codeloupe.metrics.Categorizer
import codeloupe.metrics.GapDetector
import codeloupe.metrics.LocatedGap
import codeloupe.metrics.Run
import codeloupe.metrics.ToolCall
import codeloupe.metrics.ToolResult
import codeloupe.metrics.TranscriptParser
import codeloupe.metrics.plain
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/** Stores what the ingest read of transcripts: runs, steps, hourly cost and gaps, each file in one transaction. */
class RunWriter(private val db: TranscriptDb, private val categorizer: Categorizer) {
    fun loadFiles(): MutableMap<String, FileState> = db.writer.createStatement().use { s ->
        s.executeQuery("SELECT path, size, mtime, offset, ter, state FROM files").use { r ->
            HashMap<String, FileState>().also { while (r.next()) it[r.getString(1)] = FileState(r.getLong(2), r.getLong(3), r.getLong(4), r.getString(5), r.getString(6)) }
        }
    }

    /** Remembers a new size and time of a file that has nothing new to read. */
    fun touch(found: FoundTranscript) = db.transaction { c ->
        c.prepareStatement("UPDATE files SET size = ?, mtime = ? WHERE path = ?").use { s ->
            s.setLong(1, found.size)
            s.setLong(2, found.mtime)
            s.setString(3, found.key)
            s.executeUpdate()
        }
    }

    /** Forgets a file and its run, for a transcript that was replaced by a shorter one. */
    fun forget(key: String) = db.transaction { c ->
        val id = runId(c, key)
        if (id != null) listOf("steps", "usage_hours", "gaps").forEach { table -> c.prepareStatement("DELETE FROM $table WHERE run_id = ?").use { it.setLong(1, id); it.executeUpdate() } }
        c.prepareStatement("DELETE FROM runs WHERE path = ?").use { it.setString(1, key); it.executeUpdate() }
        c.prepareStatement("DELETE FROM files WHERE path = ?").use { it.setString(1, key); it.executeUpdate() }
    }

    /** Stores [delta]; the gaps that did not exist before. */
    fun write(delta: FileDelta): List<GapRecord> = db.transaction { c ->
        val p = delta.parser
        var added = emptyList<GapRecord>()
        if (p.turns > 0) {
            val id = upsertRun(c, delta)
            insertSteps(c, id, delta.results)
            addHours(c, id, delta)
            aggregate(c, id)
            added = replaceGaps(c, id, delta.found.key, p)
        }
        saveFile(c, delta)
        added
    }

    private fun upsertRun(c: Connection, d: FileDelta): Long {
        val p = d.parser
        val startMs = millis(p.start) ?: d.found.mtime
        val endMs = millis(p.end) ?: startMs
        c.prepareStatement(
            "INSERT INTO runs(path, file, session, project, kind, role, ter, model, title, start_ms, end_ms, duration_s, turns, input, cw5m, cw1h, cache_read, output, cost, peak) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(path) DO UPDATE SET role = excluded.role, ter = excluded.ter, model = excluded.model, title = excluded.title, end_ms = excluded.end_ms, " +
                "duration_s = excluded.duration_s, turns = excluded.turns, input = excluded.input, cw5m = excluded.cw5m, cw1h = excluded.cw1h, " +
                "cache_read = excluded.cache_read, output = excluded.output, cost = excluded.cost, peak = excluded.peak",
        ).use { s ->
            var i = 0
            fun text(v: String?) = s.setString(++i, v)
            fun num(v: Long) = s.setLong(++i, v)
            text(d.found.key); text(d.found.path.fileName.toString().removeSuffix(".jsonl")); text(d.found.session); text(d.found.project); text(d.found.kind)
            text(p.role); text(d.ter); text(p.model); text(title(p.firstPrompt))
            num(startMs); num(endMs); num(Math.round((endMs - startMs) / 1000.0))
            num(p.turns.toLong()); num(p.usage.input); num(p.usage.cw5m); num(p.usage.cw1h); num(p.usage.cacheRead); num(p.usage.output); num(p.usage.cost()); num(p.peak)
            s.executeUpdate()
        }
        return runId(c, d.found.key)!!
    }

    private fun insertSteps(c: Connection, runId: Long, results: List<ToolResult>) {
        c.prepareStatement("INSERT OR REPLACE INTO steps(run_id, seq, turn, at_ms, name, category, summary, chars, ms, err, error, input, head) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { s ->
            for (r in results) {
                val category = categorizer.categorize(r.name, r.input)
                val forGaps = category in GAP_INPUT
                s.setLong(1, runId)
                s.setInt(2, r.seq)
                s.setInt(3, r.turn)
                r.atMs?.let { s.setLong(4, it) } ?: s.setNull(4, java.sql.Types.INTEGER)
                s.setString(5, r.name)
                s.setString(6, category)
                s.setString(7, StepText.summary(r.input))
                s.setInt(8, r.chars)
                s.setLong(9, r.ms)
                s.setInt(10, if (r.err) 1 else 0)
                s.setString(11, r.errText?.let { StepText.clean(it, ERROR_CHARS) })
                s.setString(12, if (forGaps) r.input.plain().toString() else null)
                s.setString(13, if (category == "codeloupe") r.head else null)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    private fun addHours(c: Connection, runId: Long, d: FileDelta) {
        val byHour = d.usages.groupBy { it.atMs / HOUR_MS }.mapValues { (_, us) -> us.sumOf { it.usage.weighted() } }
        c.prepareStatement("INSERT INTO usage_hours(run_id, hour, cost) VALUES (?, ?, ?) ON CONFLICT(run_id, hour) DO UPDATE SET cost = cost + excluded.cost").use { s ->
            byHour.forEach { (hour, cost) ->
                s.setLong(1, runId)
                s.setLong(2, hour)
                s.setDouble(3, cost)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    /** The figures that depend on the whole run: a later turn makes every earlier result carried one turn longer. */
    private fun aggregate(c: Connection, id: Long) {
        c.prepareStatement(
            "UPDATE runs SET tool_calls = (SELECT count(*) FROM steps WHERE run_id = runs.id), " +
                "tool_errors = (SELECT count(*) FROM steps WHERE run_id = runs.id AND err = 1), " +
                "tool_ms = (SELECT coalesce(sum(ms), 0) FROM steps WHERE run_id = runs.id), " +
                "result_attr = (SELECT coalesce(sum(CAST($ATTR_SQL + 0.5 AS INTEGER)), 0) FROM steps WHERE run_id = runs.id) WHERE id = ?",
        ).use { it.setLong(1, id); it.executeUpdate() }
        c.prepareStatement("UPDATE runs SET share = CAST(1000.0 * result_attr / max(cost, 1) + 0.5 AS INTEGER) WHERE id = ?").use { it.setLong(1, id); it.executeUpdate() }
    }

    private fun replaceGaps(c: Connection, id: Long, key: String, p: TranscriptParser): List<GapRecord> {
        val calls = gapCalls(c, id)
        if (calls.isEmpty() && !hasGaps(c, id)) return emptyList()
        val run = Run(key, "", p.role, null, null, p.start, p.end, p.turns, p.usage, p.peak, calls.map { it.call })
        val times = calls.associate { it.call.seq to (it.atMs ?: millis(p.start) ?: 0) }
        val found = GapDetector.locate(run)
        val old = storedGaps(c, id)
        val keep = found.associateBy(::identity)
        old.filter { identity(it) !in keep }.forEach { g -> c.prepareStatement("DELETE FROM gaps WHERE id = ?").use { it.setLong(1, g.id); it.executeUpdate() } }
        val known = old.mapTo(HashSet(), ::identity)
        val added = found.filter { identity(it) !in known }
        return added.map { g -> insertGap(c, id, g, times[g.seq] ?: 0) }
    }

    private class Stored(val call: ToolCall, val atMs: Long?)

    private fun gapCalls(c: Connection, id: Long): List<Stored> = c.prepareStatement(
        "SELECT seq, name, category, input, turn, chars, err, ms, head, at_ms FROM steps WHERE run_id = ? AND category IN ('codeloupe', 'code_read', 'code_search', 'code_search_shell') ORDER BY seq",
    ).use { s ->
        s.setLong(1, id)
        s.executeQuery().use { r ->
            buildList {
                while (r.next()) {
                    val input = r.getString(4)?.let { JsonFormat.json.parseToJsonElement(it) as JsonObject } ?: JsonObject(emptyMap())
                    val at = r.getLong(10).takeIf { !r.wasNull() }
                    add(Stored(ToolCall(r.getInt(1), r.getString(2), r.getString(3), input, null, false, null, r.getInt(5), r.getInt(6), r.getInt(7) == 1, r.getLong(8), null, r.getString(9).orEmpty(), 0, 0), at))
                }
            }
        }
    }

    private fun hasGaps(c: Connection, id: Long) = c.prepareStatement("SELECT 1 FROM gaps WHERE run_id = ? LIMIT 1").use { s -> s.setLong(1, id); s.executeQuery().use(ResultSet::next) }

    private fun storedGaps(c: Connection, id: Long): List<GapRecord> = c.prepareStatement("SELECT id, seq, turn, at_ms, tool, shape, kind, token, fallback FROM gaps WHERE run_id = ?").use { s ->
        s.setLong(1, id)
        s.executeQuery().use { r -> buildList { while (r.next()) add(GapRecord(r.getLong(1), id, "", r.getInt(2), r.getInt(3), r.getLong(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9))) } }
    }

    private fun insertGap(c: Connection, runId: Long, g: LocatedGap, atMs: Long): GapRecord {
        val id = c.prepareStatement("INSERT INTO gaps(run_id, seq, turn, at_ms, week, tool, shape, kind, token, fallback) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf("id")).use { s ->
            s.setLong(1, runId)
            s.setInt(2, g.seq)
            s.setInt(3, g.turn)
            s.setLong(4, atMs)
            s.setString(5, g.gap.week)
            s.setString(6, g.gap.tool)
            s.setString(7, g.gap.shape)
            s.setString(8, g.gap.kind)
            s.setString(9, g.gap.token)
            s.setString(10, g.fallback)
            s.executeUpdate()
            s.generatedKeys.use { it.next(); it.getLong(1) }
        }
        return GapRecord(id, runId, "", g.seq, g.turn, atMs, g.gap.tool, g.gap.shape, g.gap.kind, g.gap.token, g.fallback)
    }

    private fun identity(g: LocatedGap) = listOf(g.seq, g.gap.tool, g.gap.shape, g.gap.kind, g.gap.token)

    private fun identity(g: GapRecord) = listOf(g.seq, g.tool, g.shape, g.kind, g.token)

    private fun saveFile(c: Connection, d: FileDelta) {
        c.prepareStatement(
            "INSERT INTO files(path, size, mtime, offset, kind, project, session, ter, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime, offset = excluded.offset, ter = excluded.ter, state = excluded.state",
        ).use { s ->
            s.setString(1, d.found.key)
            s.setLong(2, d.found.size)
            s.setLong(3, d.found.mtime)
            s.setLong(4, d.offset)
            s.setString(5, d.found.kind)
            s.setString(6, d.found.project)
            s.setString(7, d.found.session)
            s.setString(8, d.ter)
            s.setString(9, JsonFormat.json.encodeToString(codeloupe.metrics.ParserSnapshot.serializer(), d.parser.snapshot()))
            s.executeUpdate()
        }
    }

    private fun runId(c: Connection, key: String): Long? = c.prepareStatement("SELECT id FROM runs WHERE path = ?").use { s ->
        s.setString(1, key)
        s.executeQuery().use { if (it.next()) it.getLong(1) else null }
    }

    private fun title(prompt: String): String = StepText.clean(prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), TITLE_CHARS)

    private fun millis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        const val HOUR_MS = 3_600_000L
        private const val ERROR_CHARS = 80
        private const val TITLE_CHARS = 120
        private val GAP_INPUT = setOf("codeloupe", "code_read", "code_search", "code_search_shell")

        /** Attributed cost of a step in SQL: [codeloupe.metrics.ToolCall.attr] for the run's current turn count. */
        const val ATTR_SQL = "chars / 4.0 * (2.0 + 0.1 * max(0, runs.turns - turn))"
    }
}
