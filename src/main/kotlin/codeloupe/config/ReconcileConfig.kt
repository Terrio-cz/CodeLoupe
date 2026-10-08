package codeloupe.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The reconciler, from `config.json` `workspaces.reconcile`:
 * `{ "auto": true, "intervalMinutes": 30, "graceMinutes": 60, "retryBaseMinutes": 1, "retryMaxMinutes": 360, "protect": [ … ] }`.
 * Nothing runs unattended unless [auto] is set; the dry-run report and confirmed cleanups work either way.
 */
data class ReconcileConfig(
    /** Clean released workspaces without being asked: on daemon start, on finished jobs and at [intervalMinutes] while clients are active. */
    val auto: Boolean = false,
    val intervalMinutes: Int = 30,
    /** A resource younger than this is never cleaned without confirmation: a fresh stack of a workspace that only looks landed. 0 switches the wait off. */
    val graceMinutes: Int = 60,
    val retryBaseMinutes: Int = 1,
    val retryMaxMinutes: Int = 360,
    val protect: List<ProtectRule> = emptyList(),
) {
    companion object {
        fun parse(section: JsonObject?): ReconcileConfig {
            section ?: return ReconcileConfig()
            fun minutes(key: String, default: Int, min: Int = 1) = (section[key] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= min } ?: default
            return ReconcileConfig(
                auto = (section["auto"] as? JsonPrimitive)?.content == "true",
                intervalMinutes = minutes("intervalMinutes", 30),
                graceMinutes = minutes("graceMinutes", 60, min = 0),
                retryBaseMinutes = minutes("retryBaseMinutes", 1),
                retryMaxMinutes = minutes("retryMaxMinutes", 360),
                protect = (section["protect"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(ProtectRule::parse) },
            )
        }
    }
}
