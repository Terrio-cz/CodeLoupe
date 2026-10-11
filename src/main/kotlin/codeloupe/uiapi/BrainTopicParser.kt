package codeloupe.uiapi

import codeloupe.JsonFormat
import codeloupe.brain.BrainEntry
import codeloupe.brain.BrainException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Reads `brain.mjs show <topic> --json`: the topic's entries (its subtopics' too) with the metadata the engine derived. */
object BrainTopicParser {
    @Serializable
    private class Shown(val entries: List<BrainEntry> = emptyList())

    fun parse(workspaceId: String, topic: String, output: String): BrainTopicView {
        val shown = try {
            JsonFormat.json.decodeFromString(Shown.serializer(), output.trim())
        } catch (e: SerializationException) {
            throw BrainException(BrainException.Kind.FAILED, "brain.mjs show did not print JSON")
        } catch (e: IllegalArgumentException) {
            throw BrainException(BrainException.Kind.FAILED, "brain.mjs show did not print JSON")
        }
        val entries = shown.entries.map { e ->
            BrainTopicView.Entry(
                mark = single(e), text = if (single(e) != null) e.text.drop(1).trimStart() else e.text, stamp = e.at?.take(DATE),
                id = e.id, kind = e.kind, task = e.task, anchors = e.anchors, superseded = e.superseded,
            )
        }
        return BrainTopicView(workspaceId, topic, shown.entries.sumOf { it.text.length.toLong() }, entries)
    }

    // "+ text" is a mark and a text; "!P2 text" and "-> text" keep their severity or arrow in the text.
    private fun single(e: BrainEntry): String? = e.marker.takeIf { it.length == 1 && e.text.length > 1 && e.text[1].isWhitespace() }

    private const val DATE = 10
}
