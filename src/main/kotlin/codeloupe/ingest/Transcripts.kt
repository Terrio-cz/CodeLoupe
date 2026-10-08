package codeloupe.ingest

import codeloupe.config.Config
import codeloupe.metrics.MetricsSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * The daemon's view of the agents' transcripts: the database, the lazy [ingest] and the [queries] the UI API reads with.
 * [waitMs] is how long a call waits for a pass that its own arrival started before it answers with what is stored.
 */
class Transcripts(
    config: Config,
    emit: (String, JsonObject) -> Unit,
    scope: CoroutineScope,
    log: (String) -> Unit,
    val waitMs: Long = DEFAULT_WAIT_MS,
) : AutoCloseable {
    private val db = TranscriptDb(config.home.resolve("transcripts.db"))
    val queries = RunQueries(db)
    val budgets = config.budgets
    val ingest = TranscriptIngest(
        { MetricsSetup(config).projectDirs(emptyList()) }, db, MetricsSetup(config).categorizer(),
        BudgetWatch(db, queries, config.budgets, emit), GapAnnouncer(emit), scope, log, config.metrics.ingestTtlMs,
    )

    suspend fun fresh() = ingest.refresh(waitMs)

    override fun close() {
        ingest.close()
        db.close()
    }

    companion object {
        const val DEFAULT_WAIT_MS = 100L
    }
}
