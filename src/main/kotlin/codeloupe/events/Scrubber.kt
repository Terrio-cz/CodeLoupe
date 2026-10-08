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

    /** The values of the secret store, set by the daemon: a text that holds one of them is masked, whatever it looks like. */
    @Volatile
    var knownValues: () -> Collection<String> = { emptyList() }

    private const val MIN_KNOWN = 6

    /** Stored values first, exactly; the pattern rules after, for what looks like a secret and is not stored. */
    fun text(value: String): String = RULES.fold(known(value)) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    /** [text] with only the given secret [values] masked, for output that must otherwise stay as it is. */
    fun mask(text: String, values: Collection<String>): String =
        values.filter { it.length >= MIN_KNOWN }.sortedByDescending { it.length }.fold(text) { acc, secret -> if (secret in acc) acc.replace(secret, MASK) else acc }

    private fun known(text: String): String = mask(text, runCatching { knownValues() }.getOrDefault(emptyList()))

    /** Every string in [element], scrubbed. */
    fun json(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { json(it.value) })
        is JsonArray -> JsonArray(element.map(::json))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(text(element.content)) else element
    }
}
