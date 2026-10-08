package codeloupe.ingest

import codeloupe.accounts.Accounts
import codeloupe.config.Config
import codeloupe.metrics.MetricsSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

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
    val accounts: Accounts = Accounts(config.home),
) : AutoCloseable {
    private val db = TranscriptDb(config.home.resolve("transcripts.db"))
    val queries = RunQueries(db)
    val usage = AccountUsage(queries)
    val budgets = config.budgets
    val ingest = TranscriptIngest(
        { projectDirs(config, accounts) }, db, MetricsSetup(config).categorizer(),
        BudgetWatch(db, queries, config.budgets, emit), GapAnnouncer(emit), scope, log, config.metrics.ingestTtlMs,
    )

    suspend fun fresh() = ingest.refresh(waitMs)

    override fun close() {
        ingest.close()
        db.close()
    }

    companion object {
        const val DEFAULT_WAIT_MS = 100L

        /** The configured or default project directories plus those of every Claude account, each directory once. */
        fun projectDirs(config: Config, accounts: Accounts): List<Path> =
            (MetricsSetup(config).projectDirs(emptyList()) + accounts.transcriptDirs()).distinctBy { dir -> runCatching { dir.toRealPath().toString() }.getOrDefault(dir.toAbsolutePath().normalize().toString()) }
    }
}
