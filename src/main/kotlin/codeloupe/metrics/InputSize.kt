package codeloupe.metrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How much a tool call sent: the characters of its parameter names and values, without the quotes and escapes of the JSON they travel in. */
object InputSize {
    fun chars(input: JsonObject): Long = input.entries.sumOf { (name, value) -> name.length + chars(value) }

    private fun chars(value: JsonElement): Long = when (value) {
        is JsonPrimitive -> value.content.length.toLong()
        is JsonObject -> chars(value)
        is JsonArray -> value.sumOf { chars(it) }
    }
}
