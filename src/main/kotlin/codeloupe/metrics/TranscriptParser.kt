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
    // Both are bounded: a message repeats on neighbouring lines only, and a call whose result never comes is not worth carrying for ever.
    // The value is the output tokens counted for the message so far.
    private val seen = LinkedHashMap<Long, Long>().apply { from?.seen?.let(::putAll) }
    private val pending = LinkedHashMap<String, Pending>().apply { from?.pending?.forEach { put(it.id, Pending(it.name, it.input, it.atMs, it.turn)) } }
    private val results = ArrayList<ToolResult>()
    private val usages = ArrayList<UsageAt>()
    private val limits = ArrayList<LimitHit>()
    private val snapshots = ArrayList<UsageSnapshot>()

    /** The fingerprint of the account uuid the transcript named in the lines read by this parser, or null when none did ([AccountIdentity]). Not part of the snapshot: the ingest keeps what an earlier read found. */
    var identity: String? = null
        private set

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
        if (type == "system" && o.containsKey("usageReport") && snapshots.size < MAX_SNAPSHOTS) snapshots += UsageReportReader.of(o, at(timestamp))
        if (o.containsKey("ownerAccountUuid") || o.containsKey("accountUuid")) AccountIdentity.of(o)?.let { identity = it }
        val message = o["message"].obj()
        o["attachment"].obj()?.let(startTracker::attachment)
        if (type == "user" && message != null) startTracker.userLine(message)
        if (type == "user" && firstPrompt.isEmpty()) message?.get("content").str()?.let { firstPrompt = it.take(PROMPT_CHARS) }
        if (type == "assistant" && message != null) {
            assistant(message, timestamp)
            limit(o, message, timestamp)
        }
        if (type == "user") for (block in message?.get("content").arr().orEmpty()) result(block.obj() ?: continue, timestamp)
        return true
    }

    /** The results and token counts read since the last call. */
    fun drain(): Pair<List<ToolResult>, List<UsageAt>> = (results.toList() to usages.toList()).also {
        results.clear()
        usages.clear()
    }

    /** The usage-limit answers read since the last call. */
    fun drainLimits(): List<LimitHit> = limits.toList().also { limits.clear() }

    /** The limits of the `/usage` reports read since the last call. */
    fun drainUsageSnapshots(): List<UsageSnapshot> = snapshots.toList().also { snapshots.clear() }

    fun snapshot() = ParserSnapshot(
        role, start, end, model, turns, usage, peak, firstPrompt, emitted, seen.toMap(),
        pending.map { (id, c) -> ParserSnapshot.Call(id, c.name, c.input.plain(), c.atMs, c.turn) }, startTracker.state(),
    )

    /** What the run started with, priced for the turns read so far; null while no turn has been read. */
    fun startContext(): StartCtx? = startTracker.context(turns)

    private fun assistant(message: JsonObject, timestamp: String?) {
        if (model == null) model = message["model"].str()
        val id = message["id"].str()?.takeIf { it.isNotEmpty() }
        if (id != null) usage(hash(id), message["usage"].obj(), timestamp, message["model"].str())
        for (block in message["content"].arr().orEmpty()) {
            val b = block.obj() ?: continue
            if (b["type"].str() == "tool_use") {
                val toolId = b["id"].str() ?: continue
                pending[toolId] = Pending(b["name"].str().orEmpty(), b["input"].obj() ?: JsonObject(emptyMap()), millis(timestamp), turns)
                if (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
            }
        }
    }

    private fun limit(line: JsonObject, message: JsonObject, timestamp: String?) {
        val text = message["content"].arr().orEmpty().firstNotNullOfOrNull { b -> b.obj()?.takeIf { it["type"].str() == "text" }?.get("text").str() }
        if (limits.size < MAX_LIMITS) LimitMessage.of(line, message, text, at(timestamp))?.let { limits += it }
    }

    /**
     * Claude Code writes a message on one line per content block, and the first line holds the streaming `output_tokens` (a
     * handful), not the final count. Everything else is the same on every line, so the first line makes the turn and each
     * later line only adds what the output has grown by.
     */
    private fun usage(key: Long, u: JsonObject?, timestamp: String?, model: String?) {
        val output = u?.get("output_tokens").num()
        val before = seen[key]
        if (before == null) {
            remember(key, output)
            turns++
            val written = u?.get("cache_creation").obj()
            val written1h = written?.get("ephemeral_1h_input_tokens").num()
            val written5m = written?.get("ephemeral_5m_input_tokens")?.let { it.num() }
                ?: maxOf(0, u?.get("cache_creation_input_tokens").num() - written1h)
            val counted = Usage(u?.get("input_tokens").num(), written5m, written1h, u?.get("cache_read_input_tokens").num(), output)
            usage += counted
            startTracker.firstTurn(counted)
            // Every line of a message holds the same thinking count, so the first one gives it.
            usages += UsageAt(at(timestamp), counted, model = model, thinking = u?.get("output_tokens_details").obj()?.get("thinking_tokens").num() ?: 0)
            peak = maxOf(peak, u?.get("input_tokens").num() + u?.get("cache_read_input_tokens").num() + u?.get("cache_creation_input_tokens").num())
        } else if (output > before) {
            seen[key] = output
            val grown = Usage(output = output - before)
            usage += grown
            usages += UsageAt(at(timestamp), grown, continuation = true, model = model)
        }
    }

    private fun at(timestamp: String?): Long = millis(timestamp) ?: millis(end) ?: 0

    /** Notes a first-seen message id with the output counted for it; the oldest ids are forgotten past [MAX_SEEN]. */
    private fun remember(key: Long, output: Long) {
        seen[key] = output
        if (seen.size > MAX_SEEN) seen.remove(seen.keys.first())
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
        const val MAX_SEEN = 5_000
        const val MAX_PENDING = 1_000
        const val MAX_LIMITS = 100
        const val MAX_SNAPSHOTS = 10_000
        const val ERROR_CHARS = 80
        const val HEAD_CHARS = 200
        const val IMAGE_CHARS = 6000
        const val FNV_OFFSET = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }
}
