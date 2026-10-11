package codeloupe.metrics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime

/** Reads the limits out of the `usageReport` of a `/usage` line (`system/local_command`); only numbers, kinds and times are taken, never the rendered text. */
internal object UsageReportReader {
    fun of(line: JsonObject, atMs: Long): List<UsageSnapshot> {
        val limits = line["usageReport"].obj()?.get("rate_limits").obj()?.get("limits").arr() ?: return emptyList()
        return limits.mapNotNull { element ->
            val limit = element.obj() ?: return@mapNotNull null
            val kind = limit["kind"].str() ?: return@mapNotNull null
            val scope = limit["scope"].obj()
            UsageSnapshot(
                atMs, kind, limit["group"].str(), scope?.get("model").obj()?.get("display_name").str() ?: scope?.get("surface").str().orEmpty(),
                (limit["percent"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull, limit["resets_at"].str()?.let(::instant),
                limit["severity"].str(), (limit["is_active"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
            )
        }
    }

    private fun instant(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
