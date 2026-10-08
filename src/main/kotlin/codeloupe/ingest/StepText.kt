package codeloupe.ingest

import codeloupe.events.Scrubber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The text of a step that leaves the daemon: what the call was about, never the content it carried (an `Edit`'s new text,
 * a `Write`'s body, a result), cut to [MAX_CHARS] after the [Scrubber] masked secrets in it.
 */
object StepText {
    const val MAX_CHARS = 200
    private const val BEFORE_CUT = 4_000
    private val SUBJECT_KEYS = listOf("command", "file_path", "path", "pattern", "query", "q", "name", "target", "url", "description", "skill", "id")
    private val WHITESPACE = Regex("\\s+")

    /** What the call asked for: its command, file, pattern or name. Empty when it names none of those. */
    fun summary(input: JsonObject): String {
        val subject = SUBJECT_KEYS.firstNotNullOfOrNull { key -> (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } }
        return subject?.let(::clean).orEmpty()
    }

    /** A free text on one line, masked and cut. */
    fun clean(text: String, max: Int = MAX_CHARS): String {
        val one = WHITESPACE.replace(text.take(BEFORE_CUT), " ").trim()
        val masked = Scrubber.text(one)
        return if (masked.length <= max) masked else masked.take(max - 1) + "…"
    }
}
