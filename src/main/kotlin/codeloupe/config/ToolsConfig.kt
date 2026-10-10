package codeloupe.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Optional tools, from `config.json` `tools`. [batch] offers the `batch` tool, which answers several independent read-only calls in one turn;
 * it is off by default because it adds to the tool list every session carries as part of its prompt.
 */
data class ToolsConfig(val batch: Boolean = false) {
    companion object {
        fun parse(file: JsonObject): ToolsConfig {
            val tools = file["tools"] as? JsonObject ?: return ToolsConfig()
            return ToolsConfig(batch = (tools["batch"] as? JsonPrimitive)?.content == "true")
        }
    }
}
