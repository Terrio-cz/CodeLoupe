package codeloupe.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Jobs and events, from `config.json`:
 * `slots` `{ "gradle-test": 2 }` (a slot not listed holds one job), `policyHook` the command (argv) that judges every
 * job like a Claude Code PreToolUse hook, `policyTimeoutMs`, and `remoteWebhooks` — the only non-local origins a
 * webhook may target (`https://hooks.example.com`).
 */
data class JobsConfig(
    val slots: Map<String, Int> = emptyMap(),
    val policyHook: List<String>? = null,
    val policyTimeoutMs: Long = 30_000,
    val remoteWebhooks: Set<String> = emptySet(),
) {
    companion object {
        fun parse(file: JsonObject): JobsConfig = JobsConfig(
            slots = (file["slots"] as? JsonObject).orEmpty()
                .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }?.let { name to it } }
                .toMap(),
            policyHook = strings(file["policyHook"])?.takeIf { it.isNotEmpty() },
            policyTimeoutMs = (file["policyTimeoutMs"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 } ?: 30_000,
            remoteWebhooks = strings(file["remoteWebhooks"]).orEmpty().map { it.trimEnd('/') }.toSet(),
        )

        private fun strings(element: Any?): List<String>? =
            (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }
}
