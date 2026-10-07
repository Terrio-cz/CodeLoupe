package codeloupe.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Tool arguments as JSON, read leniently: the CLI sends whatever the user typed. */
class ToolArgs(val json: JsonObject) {
    fun string(key: String): String? = primitive(key)?.content

    fun bool(key: String): Boolean? = primitive(key)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    fun int(key: String): Int? = primitive(key)?.doubleOrNull?.toInt()

    private fun primitive(key: String): JsonPrimitive? = (json[key] as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }
}
