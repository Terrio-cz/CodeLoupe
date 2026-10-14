package codeloupe.metrics

import kotlinx.serialization.json.JsonNull
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
class TranscriptBuilder(private val start: Instant = Instant.parse("2026-10-05T10:00:00Z"), private val model: String = "claude-test") {
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
        lines += assistantLine(input, output, cacheRead, written1h, written5m, tools.toList())
    }

    /** One assistant turn of [model] (another than the transcript's), [thinking] of its output being thinking. */
    fun turnOf(model: String, input: Long = 10, output: Long = 5, thinking: Long = 0, cacheRead: Long = 100) = apply {
        turn++
        lines += assistantLine(input, output, cacheRead, 30, 20, emptyList(), model, thinking)
    }

    /** Moves the clock of the next line [seconds] on. */
    fun later(seconds: Long) = apply { tick += seconds }

    /**
     * One assistant turn written the way Claude Code streams it: a line per content block under one message id, with the
     * output tokens growing from line to line ([outputs]); the tool calls come with the last line.
     */
    fun streamed(outputs: List<Long>, input: Long = 10, cacheRead: Long = 100, written1h: Long = 30, written5m: Long = 20, vararg tools: Triple<String, String, JsonObject>) = apply {
        turn++
        outputs.forEachIndexed { i, output ->
            lines += assistantLine(input, output, cacheRead, written1h, written5m, if (i == outputs.lastIndex) tools.toList() else emptyList())
        }
    }

    private fun assistantLine(input: Long, output: Long, cacheRead: Long, written1h: Long, written5m: Long, tools: List<Triple<String, String, JsonObject>>, model: String = this.model, thinking: Long? = null): String =
        buildJsonObject {
            put("type", "assistant")
            put("timestamp", stamp())
            putJsonObject("message") {
                put("id", "m$turn")
                put("model", model)
                putJsonObject("usage") {
                    put("input_tokens", input)
                    put("output_tokens", output)
                    thinking?.let { putJsonObject("output_tokens_details") { put("thinking_tokens", it) } }
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

    /** A usage-limit answer the way Claude Code writes it: a synthetic assistant line flagged `rate_limit`, with the quota it hit when [resetsAt] is given. */
    fun limit(text: String, resetsAt: Instant? = null, type: String = "seven_day") = apply {
        turn++
        lines += buildJsonObject {
            put("type", "assistant")
            put("timestamp", stamp())
            putJsonObject("message") {
                put("id", "m$turn")
                put("model", "<synthetic>")
                putJsonObject("usage") {
                    put("input_tokens", 0)
                    put("output_tokens", 0)
                }
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
            }
            if (resetsAt != null) putJsonObject("quotaLimits") {
                put("status", "rejected")
                put("resetsAt", resetsAt.epochSecond)
                put("rateLimitType", type)
            }
            put("error", "rate_limit")
        }.toString()
    }

    /**
     * A `/usage` report the way Claude Code writes it: a `system/local_command` line with the three meters (session, weekly, weekly per
     * model); the rendered text is a placeholder, only the structure counts. Without [limits] the report holds none, like one made while offline.
     */
    fun usageReport(session: Int?, weekly: Int?, fable: Int?, resetsAt: Instant = Instant.parse("2026-10-11T13:00:00Z"), severity: String = "normal", limits: Boolean = true) = apply {
        lines += buildJsonObject {
            put("type", "system")
            put("subtype", "local_command")
            put("timestamp", stamp())
            put("content", "usage placeholder")
            putJsonObject("usageReport") {
                putJsonObject("rate_limits") {
                    if (!limits) put("limits", JsonNull)
                    else put("limits", buildJsonArray {
                        fun meter(kind: String, group: String, percent: Int?, scope: String?, reset: String?) = add(buildJsonObject {
                            put("kind", kind)
                            put("group", group)
                            put("percent", percent)
                            put("resets_at", reset)
                            if (scope == null) put("scope", JsonNull) else putJsonObject("scope") { putJsonObject("model") { put("display_name", scope) }; put("surface", JsonNull) }
                            put("severity", severity)
                            put("is_active", kind == "weekly_all")
                        })
                        meter("session", "session", session, null, resetsAt.minusSeconds(3 * 86_400).toString())
                        meter("weekly_all", "weekly", weekly, null, resetsAt.toString().replace("Z", ".000000+00:00"))
                        meter("weekly_scoped", "weekly", fable, "Fable", null)
                    })
                }
            }
        }.toString()
    }

    /** The line that names the account of the session (`bridge-session`); [uuid] is a made-up one. */
    fun bridgeSession(uuid: String) = apply {
        lines += buildJsonObject {
            put("type", "bridge-session")
            put("timestamp", stamp())
            put("ownerAccountUuid", uuid)
        }.toString()
    }

    /** The lines written so far, for appending to a transcript that already exists. */
    fun text(): String = lines.joinToString("\n") + "\n"

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
