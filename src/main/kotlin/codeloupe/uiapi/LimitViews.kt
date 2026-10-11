package codeloupe.uiapi

import codeloupe.commands.CommandPeriod
import codeloupe.ingest.LimitSnapshots
import codeloupe.ingest.Transcripts
import codeloupe.platform.IsoTime
import java.time.Instant

/** The Limits resource: the stored `/usage` snapshots, read after the same lazy ingest pass the other transcript screens wait for. */
internal class LimitViews(private val transcripts: Transcripts, private val clock: () -> Instant = Instant::now) {
    suspend fun limits(since: String?, account: String?, limit: String?): LimitsView {
        val now = clock()
        val from = CommandPeriod.since(since ?: "7d", now)
        val size = limit?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: throw UiApiException.badRequest("limit must be 1..$MAX_LIMIT") } ?: DEFAULT_LIMIT
        val who = when (account) {
            null -> LimitSnapshots.Who.All
            UNKNOWN -> LimitSnapshots.Who.Unknown
            else -> LimitSnapshots.Who.Account(account)
        }
        transcripts.fresh()
        // One row more than asked tells whether the series was cut.
        val found = transcripts.limits.between(from.toEpochMilli(), now.toEpochMilli(), who, size + 1)
        return LimitsView(IsoTime.of(from), account, size, found.size > size, transcripts.limits.latest(who), found.takeLast(size))
    }

    companion object {
        const val UNKNOWN = "unknown"
        private const val DEFAULT_LIMIT = 5_000
        private const val MAX_LIMIT = 50_000
    }
}
