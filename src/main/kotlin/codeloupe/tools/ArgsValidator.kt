package codeloupe.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Checks MCP tool arguments against the tool's schema and drops unknown keys. */
internal object ArgsValidator {
    sealed interface Result {
        data class Valid(val args: JsonObject) : Result

        data class Invalid(val message: String) : Result
    }

    fun validate(tool: Tool, args: JsonObject?): Result {
        val properties = Tools.properties(tool)
        val given = args ?: JsonObject(emptyMap())
        for (key in tool.required) if (given[key] == null || given[key] is JsonNull) return invalid(tool, "Required at $key")
        for ((key, value) in given) {
            val schema = properties[key] as? JsonObject ?: continue
            problem(schema, value)?.let { return invalid(tool, "$it at $key") }
        }
        return Result.Valid(JsonObject(given.filterKeys { it in properties }))
    }

    private fun problem(schema: JsonObject, value: JsonElement): String? {
        val primitive = value as? JsonPrimitive
        val received = when {
            primitive == null -> if (value is JsonObject) "object" else "array"
            primitive is JsonNull -> "null"
            primitive.isString -> "string"
            primitive.booleanOrNull != null -> "boolean"
            else -> "number"
        }
        return when (schema["type"]?.jsonPrimitive?.content) {
            "string" -> when {
                received != "string" -> "Expected string, received $received"
                else -> schema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.takeIf { primitive!!.content !in it }?.let { values ->
                    "Invalid enum value. Expected ${values.joinToString(" | ") { "'$it'" }}, received '${primitive!!.content}'"
                }
            }
            "boolean" -> if (received != "boolean") "Expected boolean, received $received" else null
            "integer" -> integerProblem(schema, primitive, received)
            else -> null
        }
    }

    private fun integerProblem(schema: JsonObject, primitive: JsonPrimitive?, received: String): String? {
        if (received != "number") return "Expected number, received $received"
        val number = primitive!!.doubleOrNull ?: return "Expected number, received $received"
        if (number % 1.0 != 0.0) return "Expected integer, received float"
        schema["minimum"]?.jsonPrimitive?.int?.let { if (number < it) return "Number must be greater than or equal to $it" }
        schema["maximum"]?.jsonPrimitive?.int?.let { if (number > it) return "Number must be less than or equal to $it" }
        return null
    }

    private fun invalid(tool: Tool, detail: String) =
        Result.Invalid("MCP error -32602: Input validation error: Invalid arguments for tool ${tool.name}: $detail")
}
