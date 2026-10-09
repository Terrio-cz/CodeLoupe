package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The local API's callers, from `config.json` `api`. Everything that acts for the user (jobs, `run`, `edit`, workspaces, releases, events,
 * the app's views) needs the token in `<home>/daemon.token`. With [strict] off, the read-only code queries over MCP, `/api/<tool>` and the
 * hook endpoint also answer a caller that sends only `x-codeloupe`, so that an MCP entry made before the token existed keeps working; on a
 * machine other people log in to, set it and the token is required there too.
 */
data class ApiConfig(val strict: Boolean = false) {
    companion object {
        fun parse(file: JsonObject): ApiConfig {
            val api = file["api"] as? JsonObject ?: return ApiConfig()
            return ApiConfig(strict = (api["strict"] as? JsonPrimitive)?.content == "true")
        }
    }
}
