package codeloupe.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builders for the few JSON Schema shapes the tools use. */
internal object Schema {
    fun string(description: String? = null) = typed("string", description)

    fun boolean(description: String? = null) = typed("boolean", description)

    fun integer(min: Int, max: Int) = buildJsonObject {
        put("type", "integer")
        put("minimum", min)
        put("maximum", max)
    }

    fun strings(description: String? = null) = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        if (description != null) put("description", description)
    }

    fun enum(values: List<String>) = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    fun properties(vararg entries: Pair<String, JsonObject>) = JsonObject(linkedMapOf(*entries))

    private fun typed(type: String, description: String?) = buildJsonObject {
        put("type", type)
        if (description != null) put("description", description)
    }
}
