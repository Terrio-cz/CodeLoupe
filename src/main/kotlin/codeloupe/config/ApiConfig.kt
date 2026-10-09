package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The local API's callers, from `config.json` `api`. Everything that acts for the user (jobs, `run`, `edit`, workspaces, releases, events,
 * the app's views) needs the token in `<home>/daemon.token`. [strict], on by default, makes the read-only code queries over MCP, `/api/<tool>` and the
 * hook endpoint need it too, so another user of the machine cannot read the index. Setting `"strict": false` lets an MCP entry made before the
 * token existed keep working on a machine only one person uses.
 */
data class ApiConfig(val strict: Boolean = true) {
    companion object {
        fun parse(file: JsonObject): ApiConfig {
            val api = file["api"] as? JsonObject ?: return ApiConfig()
            return ApiConfig(strict = (api["strict"] as? JsonPrimitive)?.content != "false")
        }
    }
}
