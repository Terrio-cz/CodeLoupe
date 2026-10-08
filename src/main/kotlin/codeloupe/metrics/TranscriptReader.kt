package codeloupe.metrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.time.Instant
import kotlin.math.floor

/** Reads one Claude Code transcript (JSON lines) into a [Run]; lines that do not parse are skipped. */
class TranscriptReader(private val categorizer: Categorizer) {
    private class Pending(val name: String, val input: JsonObject, val atMs: Long?, val turn: Int)

    private class Result(val pending: Pending, val chars: Int, val err: Boolean, val ms: Long, val errText: String?, val head: String)

    fun read(source: TranscriptFile): Run {
        var role = source.role
        var start: String? = null
        var end: String? = null
        var model: String? = null
        var turns = 0
        var usage = Usage()
        var peak = 0L
        var firstPrompt = ""
        val seen = HashSet<String>()
        val pending = HashMap<String, Pending>()
        val results = ArrayList<Result>()
        Files.newBufferedReader(source.path).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.isEmpty()) continue
                val o = runCatching { Json.parseToJsonElement(line) }.getOrNull().obj() ?: continue
                val type = o["type"].str()
                if (type == "agent-setting") o["agentSetting"].str()?.takeIf { it.isNotEmpty() }?.let { role = it }
                val timestamp = o["timestamp"].str()?.takeIf { it.isNotEmpty() }
                if (timestamp != null) {
                    if (start == null) start = timestamp
                    end = timestamp
                }
                val message = o["message"].obj()
                if (type == "user" && firstPrompt.isEmpty()) message?.get("content").str()?.let { firstPrompt = it.take(PROMPT_CHARS) }
                if (type == "assistant" && message != null) {
                    if (model == null) model = message["model"].str()
                    val id = message["id"].str()?.takeIf { it.isNotEmpty() }
                    if (id != null && seen.add(id)) {
                        turns++
                        val u = message["usage"].obj()
                        val written = u?.get("cache_creation").obj()
                        val written1h = written?.get("ephemeral_1h_input_tokens").num()
                        val written5m = written?.get("ephemeral_5m_input_tokens")?.let { it.num() }
                            ?: maxOf(0, u?.get("cache_creation_input_tokens").num() - written1h)
                        usage += Usage(u?.get("input_tokens").num(), written5m, written1h, u?.get("cache_read_input_tokens").num(), u?.get("output_tokens").num())
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
                if (type == "user") for (block in message?.get("content").arr().orEmpty()) {
                    val b = block.obj() ?: continue
                    if (b["type"].str() != "tool_result") continue
                    val call = pending.remove(b["tool_use_id"].str() ?: continue) ?: continue
                    val content = b["content"]
                    val chars = resultChars(content)
                    val isError = b["is_error"]?.let { it.toString() == "true" } ?: false
                    val finishedMs = millis(timestamp)
                    val ms = if (finishedMs != null && call.atMs != null) maxOf(0, finishedMs - call.atMs) else 0
                    results += Result(call, chars, isError, ms, if (isError) (content.str() ?: chars.toString()).take(ERROR_CHARS) else null, head(content))
                }
            }
        }
        val tools = results.mapIndexed { i, r -> toolCall(i, r, turns) }
        return Run(
            file = source.path.fileName.toString(), kind = source.kind, role = role, ter = source.ter ?: TER.find(firstPrompt)?.value,
            model = model, start = start, end = end, turns = turns, usage = usage, peakContext = peak, tools = tools,
        )
    }

    private fun toolCall(seq: Int, r: Result, turns: Int): ToolCall {
        val p = r.pending
        val after = maxOf(0, turns - p.turn)
        val isShell = p.name == "Bash" || p.name == "PowerShell"
        return ToolCall(
            seq = seq, name = p.name, category = categorizer.categorize(p.name, p.input), input = p.input,
            file = p.input["file_path"].str()?.takeIf { it.isNotEmpty() },
            partial = p.name == "Read" && (p.input["offset"] != null || p.input["limit"] != null),
            cmd = if (isShell) CommandKey.of(p.input["command"].str()) else null,
            turn = p.turn, chars = r.chars, err = r.err, ms = r.ms, errText = r.errText, head = r.head,
            carried = r.chars.toLong() * after,
            attr = floor(r.chars / 4.0 * (Usage.WRITE_1H + Usage.READ * after) + 0.5).toLong(),
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

    private companion object {
        val TER = Regex("TER-\\d+")
        const val PROMPT_CHARS = 400
        const val ERROR_CHARS = 80
        const val HEAD_CHARS = 200
        const val IMAGE_CHARS = 6000
    }
}
