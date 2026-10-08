package codeloupe.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ports workspaces draw from, `config.json` `workspaces.ports`: `{ "range": [19000, 19999] }`. Without a range no port
 * is allocated; the registry then only answers that it is not configured.
 */
data class PortsConfig(val range: IntRange? = null) {
    companion object {
        fun parse(section: JsonObject?): PortsConfig {
            val bounds = (section?.get("range") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            val from = bounds.getOrNull(0) ?: return PortsConfig()
            val to = bounds.getOrNull(1) ?: return PortsConfig()
            return if (from in 1024..65535 && to in from..65535) PortsConfig(from..to) else PortsConfig()
        }
    }
}
