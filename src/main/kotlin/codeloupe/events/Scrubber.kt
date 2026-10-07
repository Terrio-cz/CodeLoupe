package codeloupe.events

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Masks what looks like a secret in text that leaves the daemon (events, webhooks, summaries): values of
 * secret-named options and variables, `Authorization` headers, bearer tokens, URL credentials and well-known
 * token shapes. Commands and their output are free text, so this is best effort, never a guarantee.
 */
object Scrubber {
    private const val MASK = "***"

    private val RULES: List<Pair<Regex, String>> = listOf(
        // https://user:pass@host
        Regex("""(?<=://)[^/\s:@]+:[^/\s@]+@""") to "$MASK@",
        // The whole header value, scheme included: `Authorization: Bearer x` → `Authorization: ***`.
        Regex("""(?i)(authorization"?\s*[:=]\s*)(?:(?:bearer|basic|token|digest)\s+)?[^\s'",;]+""") to "$1$MASK",
        Regex("""(?i)\b(bearer|basic|token)\s+[A-Za-z0-9._~+/=-]{8,}""") to "$1 $MASK",
        // --password x, --api-key=x, -token x
        Regex("""(?i)(--?[\w-]*(?:passw(?:or)?d|secret|token|api-?key|credential)[\w-]*)(=|\s+)("[^"]*"|'[^']*'|\S+)""") to "$1$2$MASK",
        // PASSWORD=x, api_key: x, "token": "x", Authorization: x
        Regex("""(?i)([\w.-]*(?:passw(?:or)?d|secret|token|api[_-]?key|credential|authorization)[\w.-]*"?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;&'"]+)""") to "$1$MASK",
        Regex("""\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|perm:[A-Za-z0-9._=-]{10,})""") to MASK,
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""") to MASK,
    )

    fun text(value: String): String = RULES.fold(value) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    /** Every string in [element], scrubbed. */
    fun json(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { json(it.value) })
        is JsonArray -> JsonArray(element.map(::json))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(text(element.content)) else element
    }
}
