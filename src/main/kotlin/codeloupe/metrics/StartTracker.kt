package codeloupe.metrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor

/**
 * Collects what a transcript holds before its first assistant turn: the characters of each attachment by type, of the
 * system prompt and of the first user message, then the first turn's token counts. After that turn it ignores everything.
 */
class StartTracker(from: State? = null) {
    /** Where the tracker stands, so a parser resumed from a snapshot goes on. */
    @Serializable
    data class State(val before: Boolean, val chars: Map<String, Long>, val first: Usage?)

    private var before = from?.before ?: true
    private val chars = LinkedHashMap<String, Long>().apply { from?.chars?.let(::putAll) }
    private var first: Usage? = from?.first

    /** An `attachment` of a transcript line. The system prompt counts once; anything else by its type, its content or, lacking one, itself. */
    fun attachment(att: JsonObject) {
        if (!before) return
        val type = att["type"].str()
        if (type == "prompt_snapshot") {
            if ((chars["body"] ?: 0L) == 0L) add("body", att["systemPrompt"].arr().orEmpty().sumOf { it.str()?.length?.toLong() ?: 0L })
        } else {
            add(type ?: "undefined", JsStringify.length(CONTENT_KEYS.firstNotNullOfOrNull { att[it]?.takeUnless { v -> v is JsonNull } } ?: att))
        }
    }

    /** The text of a user line before the first turn: a string message, or the text blocks of a list. */
    fun userLine(message: JsonObject) {
        if (!before) return
        val content: JsonElement? = message["content"]
        add("prompt", content.str()?.length?.toLong() ?: content.arr()?.sumOf { b -> if (b.obj()?.get("type").str() == "text") b.obj()?.get("text").str()?.length?.toLong() ?: 0L else 0L } ?: 0L)
    }

    /** The first assistant turn, with the tokens it was charged. */
    fun firstTurn(counted: Usage) {
        if (!before) return
        first = counted
        before = false
    }

    fun state() = State(before, chars.toMap(), first)

    /** The context the run started with, priced for a run of [turns] turns; null when the first turn counted no tokens. */
    fun context(turns: Int): StartCtx? {
        val f = first ?: return null
        val s = f.input + f.cw5m + f.cw1h + f.cacheRead
        if (s == 0L) return null
        val cost = f.input * 1.0 + f.cw5m * 1.25 + f.cw1h * 2.0 + f.cacheRead * 0.1 + s * 0.1 * maxOf(0, turns - 1)
        return StartCtx(s, floor(cost + 0.5).toLong(), chars.toMap())
    }

    private fun add(key: String, n: Long) {
        chars[key] = (chars[key] ?: 0L) + n
    }

    private companion object {
        // `att.content ?? att.addedLines ?? …` in the workspace script.
        val CONTENT_KEYS = listOf("content", "addedLines", "addedBlocks", "addedNames", "context", "snapshot")
    }
}
