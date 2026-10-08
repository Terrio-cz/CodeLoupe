package codeloupe.ingest

import codeloupe.TestRepos
import codeloupe.config.BudgetsConfig
import codeloupe.metrics.Categorizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** A transcript ingest on a temporary database over `<root>/p`, with the events it emits collected. */
class IngestRig(
    val root: Path = TestRepos.tmpDir("ingest"),
    budgets: BudgetsConfig = BudgetsConfig(),
    now: Instant = Instant.parse("2026-10-08T12:00:00Z"),
    categorizer: Categorizer = Categorizer(Categorizer.DEFAULT_RULES),
    val dbFile: Path = root.resolve("transcripts.db"),
) : AutoCloseable {
    val events = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val project: Path = root.resolve("p")
    val db = TranscriptDb(dbFile)
    val queries = RunQueries(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ingest = TranscriptIngest(
        { listOf(project) }, db, categorizer,
        BudgetWatch(db, queries, budgets, { type, data -> events += type to data }, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC)),
        GapAnnouncer { type, data -> events += type to data }, scope, ttlMs = 0,
    )

    fun runs(sort: RunSort = RunSort.START): List<RunRecord> = queries.runs(0, null, null, sort, 0, 500).items

    fun steps(runId: Long): List<StepRecord> = queries.steps(runId, StepSort.SEQ, 0, 500).items

    fun eventTypes(): List<String> = events.map { it.first }

    fun scalar(sql: String): Long = db.reader.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getLong(1) } }

    override fun close() {
        ingest.close()
        db.close()
    }
}
