package codeloupe.tools

import codeloupe.repo.Registry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * `batch`: several independent read-only calls answered in one turn. Each call runs as if made alone (same checks, same per-caller memory
 * of what was read), in the order given; an error or a refusal stays in its own part. Only the tools in [READ_ONLY] run: anything that
 * writes, starts a process or names a secret, and `batch` itself, is refused.
 */
class BatchTool(
    private val offered: () -> List<Tool>,
    /** Runs one validated call the way a call of its own would run (recorded, timed, with the daemon's default root). */
    private val run: suspend (Tool, ToolArgs) -> Outcome,
) : Tool {
    data class Outcome(val ok: Boolean, val text: String)

    override val name = "batch"
    override val description = "Several independent read-only calls in one turn: ${READ_ONLY.joinToString(", ")}. Answers come back in order, " +
        "each labelled; an error stays in its part. Use it when you would otherwise issue these calls one after another without needing one " +
        "answer to form the next."
    override val properties = Schema.properties(
        "calls" to Schema.array(
            buildJsonObject {
                put("type", "object")
                put("properties", Schema.properties("tool" to Schema.string(), "args" to Schema.obj("The tool's own arguments (root may differ per call)")))
                put("required", JsonArray(listOf(JsonPrimitive("tool"))))
            },
            "1 to $MAX_CALLS calls",
        ),
    )
    override val required = listOf("calls")

    /** `root` is the default for calls that name none; a call may carry its own. */
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val calls = (args.json["calls"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return "error: pass calls: an array of {tool, args}"
        if (calls.size > MAX_CALLS) return "error: at most $MAX_CALLS calls per batch (got ${calls.size}); split it"
        val cap = maxOf(MIN_PART, TOTAL / calls.size)
        val parts = mutableListOf<String>()
        for ((index, call) in calls.withIndex()) {
            val (label, ok, text) = part(call, root)
            parts += "[${index + 1}] $label${if (ok) "" else " (failed)"}\n${cut(text, cap)}"
        }
        return parts.joinToString("\n")
    }

    private suspend fun part(call: JsonElement, root: String): Triple<String, Boolean, String> {
        val spec = call as? JsonObject ?: return Triple("?", false, "error: a call is {tool, args}")
        val toolName = (spec["tool"] as? JsonPrimitive)?.content.orEmpty().trim()
        if (toolName !in READ_ONLY) return Triple(toolName.ifEmpty { "?" }, false, "error: \"$toolName\" is not a read-only tool batch runs: ${READ_ONLY.joinToString(", ")}")
        val tool = offered().firstOrNull { it.name == toolName && !it.mutating } ?: return Triple(toolName, false, "error: \"$toolName\" is not available in this daemon")
        val given = (spec["args"] as? JsonObject) ?: JsonObject(spec.filterKeys { it != "tool" })
        val withRoot = if (root.isEmpty() || given["root"] != null) given else JsonObject(given + ("root" to JsonPrimitive(root)))
        val label = toolName + summary(withRoot)
        return when (val checked = ArgsValidator.validate(tool, withRoot)) {
            is ArgsValidator.Result.Invalid -> Triple(label, false, "error: ${checked.message}")
            is ArgsValidator.Result.Valid -> try {
                run(tool, ToolArgs(checked.args)).let { Triple(label, it.ok, it.text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Triple(label, false, "error: ${e.message}")
            }
        }
    }

    /** The first argument that names the target, so a part reads `symbol OrderService.place` rather than `symbol`. */
    private fun summary(args: JsonObject): String {
        val key = LABEL_KEYS.firstOrNull { (args[it] as? JsonPrimitive)?.content?.isNotEmpty() == true } ?: return ""
        val value = (args[key] as JsonPrimitive).content.replace('\n', ' ')
        return " ${if (value.length > LABEL_MAX) value.take(LABEL_MAX - 1) + "…" else value}"
    }

    private fun cut(text: String, cap: Int): String {
        if (text.length <= cap) return text
        val head = text.take(cap).substringBeforeLast('\n').ifEmpty { text.take(cap) }
        return "$head\n… ${text.length - head.length} more chars; call it alone for the rest"
    }

    companion object {
        const val MAX_CALLS = 8
        private const val TOTAL = 48_000
        private const val MIN_PART = 4_000
        private const val LABEL_MAX = 60
        private val LABEL_KEYS = listOf("q", "name", "target", "pattern", "path", "id", "query")

        /** The tools a batch may run: code and document reads, issue reads; nothing that writes, runs a process or names a secret. */
        val READ_ONLY = listOf("find", "grep", "outline", "symbol", "context", "usages", "calls", "hierarchy", "changes", "doc", "issue", "task_context", "task_code")
    }
}
