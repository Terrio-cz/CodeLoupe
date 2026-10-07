package codeloupe.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Tool arguments as JSON, read leniently: the CLI sends whatever the user typed. */
class ToolArgs(val json: JsonObject) {
    fun string(key: String): String? = primitive(key)?.content

    fun bool(key: String): Boolean? = primitive(key)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    /** A string array, or one comma-separated string (what a CLI user types). */
    fun strings(key: String): List<String> = when (val value = json[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> value.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    fun int(key: String): Int? = primitive(key)?.doubleOrNull?.toInt()

    private fun primitive(key: String): JsonPrimitive? = (json[key] as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }
}
