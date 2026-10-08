package codeloupe.metrics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** A transcript written line by line the way Claude Code does: assistant turns that call tools, user lines that answer them. */
class TranscriptBuilder(private val start: Instant = Instant.parse("2026-10-05T10:00:00Z")) {
    private val lines = ArrayList<String>()
    private var turn = 0
    private var tick = 0L

    private fun stamp() = start.plusSeconds(tick++).toString()

    fun prompt(text: String) = apply {
        lines += buildJsonObject {
            put("type", "user")
            put("timestamp", stamp())
            putJsonObject("message") { put("content", text) }
        }.toString()
    }

    /** One assistant turn with its own message id, calling [tools] (id, name, input). */
    fun turn(input: Long = 10, output: Long = 5, cacheRead: Long = 100, written1h: Long = 30, written5m: Long = 20, vararg tools: Triple<String, String, JsonObject>) = apply {
        turn++
        lines += buildJsonObject {
            put("type", "assistant")
            put("timestamp", stamp())
            putJsonObject("message") {
                put("id", "m$turn")
                put("model", "claude-test")
                putJsonObject("usage") {
                    put("input_tokens", input)
                    put("output_tokens", output)
                    put("cache_read_input_tokens", cacheRead)
                    put("cache_creation_input_tokens", written1h + written5m)
                    putJsonObject("cache_creation") {
                        put("ephemeral_1h_input_tokens", written1h)
                        put("ephemeral_5m_input_tokens", written5m)
                    }
                }
                put("content", buildJsonArray {
                    tools.forEach { (id, name, args) ->
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", id)
                            put("name", name)
                            put("input", args)
                        })
                    }
                })
            }
        }.toString()
    }

    fun result(id: String, text: String, error: Boolean = false) = apply {
        lines += buildJsonObject {
            put("type", "user")
            put("timestamp", stamp())
            putJsonObject("message") {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", text)
                        if (error) put("is_error", true)
                    })
                })
            }
        }.toString()
    }

    fun write(file: Path): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, lines.joinToString("\n") + "\n")
        return file
    }

    companion object {
        fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
    }
}
