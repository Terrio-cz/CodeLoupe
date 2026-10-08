package codeloupe.config

import codeloupe.docker.ResourceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resources the reconciler must never touch, whoever owns them: `{ "match": "^terrio-importer(_|$)", "kinds": ["volume"] }`
 * keeps the user's shared Terrio stack. [match] is tried (case-insensitively) against each name of a resource and its
 * compose project; no `kinds`: every kind. A protected resource is listed as protected and left alone.
 */
data class ProtectRule(val match: Regex, val kinds: Set<ResourceKind> = emptySet()) {
    /** [kind] is null for a directory: only a rule without `kinds` covers those. */
    fun covers(kind: ResourceKind?, names: List<String>, project: String?): Boolean =
        (kinds.isEmpty() || (kind != null && kind in kinds)) && (names + listOfNotNull(project)).any { match.containsMatchIn(it) }

    companion object {
        /** The rule written as JSON, null when it has no usable pattern. */
        fun parse(entry: JsonObject): ProtectRule? {
            val match = (entry["match"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() } ?: return null
            val kinds = (entry["kinds"] as? JsonArray).orEmpty().mapNotNull { k ->
                (k as? JsonPrimitive)?.content?.let { name -> ResourceKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            }.toSet()
            return ProtectRule(match, kinds)
        }
    }
}
