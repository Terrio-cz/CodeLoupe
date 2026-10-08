package codeloupe.metrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Lenient reads of transcript lines: a missing or differently typed field is just absent. */
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.num(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: 0

/** The plain values of a tool call's input, long texts cut: what categories and the gap detector read of it. */
internal fun JsonObject.plain(): JsonObject = JsonObject(
    filterValues { it is JsonPrimitive }.mapValues { (_, v) -> (v as JsonPrimitive).takeIf { it.isString }?.let { JsonPrimitive(it.content.take(KEPT_CHARS)) } ?: v },
)

private const val KEPT_CHARS = 2000
