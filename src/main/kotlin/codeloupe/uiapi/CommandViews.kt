package codeloupe.uiapi

import codeloupe.commands.CommandFilter
import codeloupe.commands.CommandPeriod
import codeloupe.commands.CommandSort
import codeloupe.ingest.Transcripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The Commands tab of the Runs screen: which commands agents use and what they cost, from the same report as `codeloupe metrics commands`.
 * A report reads every step of the period (about two seconds for a month of every role), so the last few are kept for [TTL_MS] and a screen that asks again within it, between two ingest passes, is served from there.
 */
internal class CommandViews(private val transcripts: Transcripts, private val clock: () -> Instant = Instant::now) {
    private val cache = LinkedHashMap<String, Pair<Long, CommandsView>>()

    suspend fun commands(since: String?, role: String?, top: String?, sort: String?): CommandsView {
        val now = clock()
        val from = CommandPeriod.since(since ?: "7d", now)
        val size = top?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_TOP } ?: throw UiApiException.badRequest("top must be 1..$MAX_TOP") } ?: DEFAULT_TOP
        val order = sort?.let { CommandSort.of(it) ?: throw UiApiException.badRequest("sort must be one of ${CommandSort.entries.joinToString { s -> s.param }}") } ?: CommandSort.COST
        val filter = CommandFilter(from.toEpochMilli(), null, role?.trim()?.takeIf { it.isNotEmpty() })
        transcripts.fresh()
        val ingest = transcripts.ingest.status()
        val key = listOf(since, filter.role, size, order).joinToString("|")
        if (!ingest.running) cached(key, now.toEpochMilli())?.let { return it }
        val view = withContext(Dispatchers.IO) {
            CommandsView(transcripts.commands.report(filter, size, order), transcripts.queries.roles(), ingest)
        }
        // A pass in progress is still changing what the report reads: only a report made between passes is worth keeping.
        return if (ingest.running) view else remember(key, now.toEpochMilli(), view)
    }

    @Synchronized
    private fun cached(key: String, at: Long): CommandsView? = cache[key]?.takeIf { at - it.first < TTL_MS }?.second

    @Synchronized
    private fun remember(key: String, at: Long, view: CommandsView): CommandsView {
        cache[key] = at to view
        while (cache.size > KEPT) cache.remove(cache.keys.first())
        return view
    }

    private companion object {
        const val DEFAULT_TOP = 20
        const val MAX_TOP = 100
        const val TTL_MS = 20_000L
        const val KEPT = 8
    }
}
