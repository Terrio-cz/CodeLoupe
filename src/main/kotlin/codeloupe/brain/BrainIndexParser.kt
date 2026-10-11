package codeloupe.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads `brain.mjs index --json`. A brain whose CLI predates `--json` prints `topic  entries  about` lines; those are read
 * too (no byte sizes), so the screen works across the CLI's versions.
 */
object BrainIndexParser {
    class Topic(val topic: String, val entries: Int, val bytes: Long, val about: String)

    class Index(val workspace: String?, val topics: List<Topic>)

    private val textLine = Regex("""^(\S+)\s+(\d+)\s*(.*)$""")

    fun parse(output: String): Index {
        val text = output.trim()
        if (!text.startsWith("{")) return Index(null, text.lineSequence().mapNotNull(::line).toList())
        val root = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse { throw BrainException(BrainException.Kind.FAILED, "brain.mjs index did not print JSON") }
        val topics = (root["topics"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = str(o["topic"])?.takeIf { BrainTopicName.valid(it) } ?: return@mapNotNull null
            Topic(name, (o["entries"] as? JsonPrimitive)?.intOrNull ?: 0, (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0, str(o["about"]).orEmpty())
        }
        return Index(str(root["workspace"]), topics)
    }

    private fun line(text: String): Topic? = textLine.find(text.trim())?.let { m -> m.groupValues[1].takeIf(BrainTopicName::valid)?.let { Topic(it, m.groupValues[2].toInt(), 0, m.groupValues[3].trim()) } }

    private fun str(element: Any?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
