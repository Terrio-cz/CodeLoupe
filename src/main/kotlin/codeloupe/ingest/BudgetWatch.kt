package codeloupe.ingest

import codeloupe.config.BudgetsConfig
import codeloupe.events.EventTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

/**
 * Finds the days and runs that went over `budgets.dailyWeighted` / `budgets.runWeighted` and announces each exactly once:
 * the key of a breach (`day:2026-10-08`, `run:12`) is stored, and a key that is stored is never announced again, also not
 * after a restart. Only today, yesterday and runs that ended in the last day are looked at, so old history is never announced.
 */
class BudgetWatch(
    private val db: TranscriptDb,
    private val queries: RunQueries,
    private val budgets: BudgetsConfig,
    private val emit: (String, JsonObject) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** [quiet] stores the breaches that exist without announcing them: the first pass reads history. */
    fun check(quiet: Boolean) {
        budgets.dailyWeighted?.let { days(it, quiet) }
        budgets.runWeighted?.let { runs(it, quiet) }
    }

    private fun days(limit: Long, quiet: Boolean) {
        val today = clock.instant().atZone(zone).toLocalDate()
        for (day in listOf(today.minusDays(1), today)) {
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val used = Math.round(queries.hours(Math.floorDiv(from, RunWriter.HOUR_MS), Math.floorDiv(to, RunWriter.HOUR_MS)).values.sum())
            if (used > limit) {
                announce("day:$day", quiet, buildJsonObject {
                    put("scope", "day")
                    put("day", day.toString())
                    put("weighted", used)
                    put("limit", limit)
                })
            }
        }
    }

    private fun runs(limit: Long, quiet: Boolean) {
        val since = clock.instant().minus(Duration.ofDays(1)).toEpochMilli()
        db.writer.prepareStatement("SELECT id, role, ter, title, cost FROM runs WHERE cost > ? AND end_ms >= ? ORDER BY id").use { s ->
            s.setLong(1, limit)
            s.setLong(2, since)
            s.executeQuery().use { r ->
                while (r.next()) {
                    announce("run:${r.getLong(1)}", quiet, buildJsonObject {
                        put("scope", "run")
                        put("runId", r.getLong(1).toString())
                        put("role", r.getString(2))
                        r.getString(3)?.let { put("ter", it) }
                        put("title", r.getString(4))
                        put("weighted", r.getLong(5))
                        put("limit", limit)
                    })
                }
            }
        }
    }

    private fun announce(key: String, quiet: Boolean, data: JsonObject) {
        val fresh = db.writer.prepareStatement("INSERT OR IGNORE INTO breaches(key, at) VALUES (?, ?)").use { s ->
            s.setString(1, key)
            s.setString(2, clock.instant().toString())
            s.executeUpdate() == 1
        }
        if (fresh && !quiet) emit(EventTypes.BUDGET_BREACH, data)
    }
}
