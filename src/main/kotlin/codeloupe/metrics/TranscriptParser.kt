package codeloupe.metrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Reads the lines of one Claude Code transcript in order and keeps what a [Run] is made of. Lines that do not parse are
 * skipped. The state can be saved ([snapshot]) and picked up later, so a transcript that grew is read from its last
 * offset, not from the start.
 */
class TranscriptParser(role: String, from: ParserSnapshot? = null) {
    private class Pending(val name: String, val input: JsonObject, val atMs: Long?, val turn: Int)

    var role: String = from?.role ?: role
        private set
    var start: String? = from?.start
        private set
    var end: String? = from?.end
        private set
    var model: String? = from?.model
        private set
    var turns: Int = from?.turns ?: 0
        private set
    var usage: Usage = from?.usage ?: Usage()
        private set
    var peak: Long = from?.peak ?: 0
        private set
    var firstPrompt: String = from?.firstPrompt.orEmpty()
        private set

    private val startTracker = StartTracker(from?.startCtx)

    private var emitted = from?.emitted ?: 0
    private val seen = HashSet<Long>().apply { from?.seen?.let(::addAll) }
    private val pending = HashMap<String, Pending>().apply { from?.pending?.forEach { put(it.id, Pending(it.name, it.input, it.atMs, it.turn)) } }
    private val results = ArrayList<ToolResult>()
    private val usages = ArrayList<UsageAt>()

    /** False when [line] is not a JSON object, in which case nothing changed. */
    fun feed(line: String): Boolean {
        val o = runCatching { Json.parseToJsonElement(line) }.getOrNull().obj() ?: return false
        val type = o["type"].str()
        if (type == "agent-setting") o["agentSetting"].str()?.takeIf { it.isNotEmpty() }?.let { role = it }
        val timestamp = o["timestamp"].str()?.takeIf { it.isNotEmpty() }
        if (timestamp != null) {
            if (start == null) start = timestamp
            end = timestamp
        }
        val message = o["message"].obj()
        o["attachment"].obj()?.let(startTracker::attachment)
        if (type == "user" && message != null) startTracker.userLine(message)
        if (type == "user" && firstPrompt.isEmpty()) message?.get("content").str()?.let { firstPrompt = it.take(PROMPT_CHARS) }
        if (type == "assistant" && message != null) assistant(message, timestamp)
        if (type == "user") for (block in message?.get("content").arr().orEmpty()) result(block.obj() ?: continue, timestamp)
        return true
    }

    /** The results and token counts read since the last call. */
    fun drain(): Pair<List<ToolResult>, List<UsageAt>> = (results.toList() to usages.toList()).also {
        results.clear()
        usages.clear()
    }

    fun snapshot() = ParserSnapshot(
        role, start, end, model, turns, usage, peak, firstPrompt, emitted, seen.toList(),
        pending.map { (id, c) -> ParserSnapshot.Call(id, c.name, c.input.plain(), c.atMs, c.turn) }, startTracker.state(),
    )

    /** What the run started with, priced for the turns read so far; null while no turn has been read. */
    fun startContext(): StartCtx? = startTracker.context(turns)

    private fun assistant(message: JsonObject, timestamp: String?) {
        if (model == null) model = message["model"].str()
        val id = message["id"].str()?.takeIf { it.isNotEmpty() }
        if (id != null && seen.add(hash(id))) {
            turns++
            val u = message["usage"].obj()
            val written = u?.get("cache_creation").obj()
            val written1h = written?.get("ephemeral_1h_input_tokens").num()
            val written5m = written?.get("ephemeral_5m_input_tokens")?.let { it.num() }
                ?: maxOf(0, u?.get("cache_creation_input_tokens").num() - written1h)
            val counted = Usage(u?.get("input_tokens").num(), written5m, written1h, u?.get("cache_read_input_tokens").num(), u?.get("output_tokens").num())
            usage += counted
            startTracker.firstTurn(counted)
            usages += UsageAt(millis(timestamp) ?: millis(end) ?: 0, counted)
            peak = maxOf(peak, u?.get("input_tokens").num() + u?.get("cache_read_input_tokens").num() + u?.get("cache_creation_input_tokens").num())
        }
        for (block in message["content"].arr().orEmpty()) {
            val b = block.obj() ?: continue
            if (b["type"].str() == "tool_use") {
                val toolId = b["id"].str() ?: continue
                pending[toolId] = Pending(b["name"].str().orEmpty(), b["input"].obj() ?: JsonObject(emptyMap()), millis(timestamp), turns)
            }
        }
    }

    private fun result(b: JsonObject, timestamp: String?) {
        if (b["type"].str() != "tool_result") return
        val call = pending.remove(b["tool_use_id"].str() ?: return) ?: return
        val content = b["content"]
        val chars = resultChars(content)
        val isError = b["is_error"]?.let { it.toString() == "true" } ?: false
        val finishedMs = millis(timestamp)
        val ms = if (finishedMs != null && call.atMs != null) maxOf(0, finishedMs - call.atMs) else 0
        results += ToolResult(
            emitted++, call.name, call.input, call.turn, call.atMs, chars, isError, ms,
            if (isError) (content.str() ?: chars.toString()).take(ERROR_CHARS) else null, head(content),
        )
    }

    private fun millis(timestamp: String?): Long? = timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Characters a result holds: its text, an image counted as 6000. */
    private fun resultChars(content: JsonElement?): Int = content.str()?.length
        ?: content.arr()?.sumOf { b ->
            when (b.obj()?.get("type").str()) {
                "text" -> b.obj()?.get("text").str()?.length ?: 0
                "image" -> IMAGE_CHARS
                else -> 0
            }
        } ?: 0

    private fun head(content: JsonElement?): String = (content.str() ?: content.arr()?.firstNotNullOfOrNull { b ->
        b.obj()?.takeIf { it["type"].str() == "text" }?.get("text").str()
    }.orEmpty()).take(HEAD_CHARS)

    private fun hash(id: String): Long {
        var h = FNV_OFFSET
        for (c in id) h = (h xor c.code.toLong()) * FNV_PRIME
        return h
    }

    private companion object {
        const val PROMPT_CHARS = 400
        const val ERROR_CHARS = 80
        const val HEAD_CHARS = 200
        const val IMAGE_CHARS = 6000
        const val FNV_OFFSET = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }
}
