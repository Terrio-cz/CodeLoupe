package codeloupe.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Jobs and events, from `config.json`:
 * `slots` `{ "gradle-test": 2 }` (a slot not listed holds one job; the daemon takes a change without a restart), `policyHook` the
 * command (argv) that judges every job like a Claude Code PreToolUse hook, `policyTimeoutMs`, and `remoteWebhooks` — the only
 * non-local origins a webhook may target (`https://hooks.example.com`). [invalidSlots] are `slots` entries that are not a positive
 * whole number, kept as written so that status can show them.
 */
data class JobsConfig(
    val slots: Map<String, Int> = emptyMap(),
    val policyHook: List<String>? = null,
    val policyTimeoutMs: Long = 30_000,
    val remoteWebhooks: Set<String> = emptySet(),
    val invalidSlots: Map<String, String> = emptyMap(),
) {
    companion object {
        fun parse(file: JsonObject): JobsConfig {
            val slots = (file["slots"] as? JsonObject).orEmpty()
            val valid = slots.mapNotNull { (name, value) -> capacity(value)?.let { name to it } }.toMap()
            return JobsConfig(
                slots = valid,
                policyHook = strings(file["policyHook"])?.takeIf { it.isNotEmpty() },
                policyTimeoutMs = (file["policyTimeoutMs"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 } ?: 30_000,
                remoteWebhooks = strings(file["remoteWebhooks"]).orEmpty().map { it.trimEnd('/') }.toSet(),
                invalidSlots = slots.filterKeys { it !in valid }.mapValues { (_, value) -> value.toString().take(MAX_SHOWN) },
            )
        }

        private fun capacity(value: Any?): Int? = (value as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }

        private fun strings(element: Any?): List<String>? =
            (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        private const val MAX_SHOWN = 40
    }
}
