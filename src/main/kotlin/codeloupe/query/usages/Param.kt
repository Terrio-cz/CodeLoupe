package codeloupe.query.usages

import codeloupe.query.DeclRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A value parameter as the index records it. */
internal data class Param(val type: String, val default: Boolean, val vararg: Boolean) {
    companion object {
        fun of(d: DeclRow): List<Param> =
            Json.parseToJsonElement(d.params?.takeIf { it.isNotEmpty() } ?: "[]").jsonArray.map { it.jsonObject }.map { p ->
                Param(
                    type = p["type"]?.jsonPrimitive?.content.orEmpty(),
                    default = p["default"]?.jsonPrimitive?.booleanOrNull == true,
                    vararg = p["vararg"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
    }
}
