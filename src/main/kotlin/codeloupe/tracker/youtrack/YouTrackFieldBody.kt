package codeloupe.tracker.youtrack

import codeloupe.tracker.TrackerException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneOffset

/** The JSON YouTrack wants to set a custom field, by the field's `$type`; text in, text out. A blank value clears the field. */
internal object YouTrackFieldBody {
    private const val TYPE = "\$type"

    fun customField(name: String, type: String, text: String): JsonObject = buildJsonObject {
        put(TYPE, type)
        put("name", name)
        put("value", value(name, type, text.trim()))
    }

    private fun value(name: String, type: String, text: String): JsonElement {
        val kind = type.removeSuffix("IssueCustomField")
        if (kind.startsWith("Multi")) return JsonArray(text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { item(name, kind.removePrefix("Multi"), it) })
        return if (text.isEmpty()) JsonNull else item(name, kind.removePrefix("Single"), text)
    }

    private fun item(name: String, kind: String, text: String): JsonElement = when (kind) {
        "State", "Enum", "Build", "Version", "Owned", "Group" -> named("name", text)
        "User" -> named("login", text)
        "Text" -> named("text", text)
        "Period" -> named("presentation", text)
        "Simple" -> text.toLongOrNull()?.let(::JsonPrimitive) ?: text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
        "Date" -> JsonPrimitive(text.toLongOrNull() ?: date(name, text))
        else -> throw TrackerException("field $name has a type CodeLoupe cannot write ($kind)")
    }

    private fun named(key: String, text: String) = buildJsonObject { put(key, text) }

    private fun date(name: String, text: String): Long =
        runCatching { LocalDate.parse(text.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrElse { throw TrackerException("field $name: a date like 2026-10-07 or epoch milliseconds, not '$text'") }
}
