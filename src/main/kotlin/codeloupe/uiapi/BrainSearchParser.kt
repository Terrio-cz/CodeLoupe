package codeloupe.uiapi

import codeloupe.brain.BrainException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Reads `brain.mjs search … --json`. */
object BrainSearchParser {
    fun parse(workspaceId: String, output: String): BrainSearchView {
        val root = runCatching { Json.parseToJsonElement(output.trim()) as JsonObject }.getOrElse { throw BrainException(BrainException.Kind.FAILED, "brain.mjs search did not print JSON") }
        val hits = (root["hits"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val where = str(o["where"]) ?: return@mapNotNull null
            BrainSearchView.Hit(where, str(o["topic"]), str(o["task"]), int(o["line"]), str(o["text"]).orEmpty(), int(o["hits"]) ?: 1)
        }
        val terms = (root["terms"] as? JsonArray).orEmpty().mapNotNull(::str)
        return BrainSearchView(workspaceId, terms, hits, int(root["more"]) ?: 0)
    }

    private fun str(element: Any?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun int(element: Any?): Int? = (element as? JsonPrimitive)?.intOrNull
}
