package codeloupe.jobs

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The one MCP tool for jobs: start, status, cancel. Waiting is not an MCP call (a tool call would hold a turn open):
 * `codeloupe job wait <id>` in a background Bash task, or the `job.finished` event.
 */
class JobTool(private val jobs: JobRunner) {
    /** A [denial] answers every call with that text instead of touching a job: the caller has not shown it may. */
    fun register(server: Server, denial: String? = null) {
        server.addTool(name = NAME, description = DESCRIPTION, inputSchema = ToolSchema(properties = PROPERTIES, required = listOf("action"))) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            if (denial != null) return@addTool result(denial, isError = true)
            try {
                when (string(args, "action")) {
                    "start" -> start(args)
                    "status" -> status(string(args, "id"))
                    "cancel" -> cancel(string(args, "id") ?: throw IllegalArgumentException("cancel needs id"))
                    else -> result("action must be start, status or cancel", isError = true)
                }
            } catch (e: IllegalArgumentException) {
                result("error: ${e.message}", isError = true)
            }
        }
    }

    private suspend fun start(args: JsonObject): CallToolResult {
        val request = JobRequest(
            command = strings(args, "command"),
            cwd = string(args, "cwd") ?: throw IllegalArgumentException("start needs cwd: the absolute directory to run in"),
            env = (args["env"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() },
            slot = string(args, "slot"),
            then = strings(args, "then"),
            onFailure = strings(args, "onFailure"),
            wake = string(args, "wake"),
            tag = string(args, "tag"),
        )
        return when (val submission = jobs.submit(request)) {
            is Submission.Accepted -> result(
                JobReport.line(submission.job, jobs.ahead(submission.job)) +
                    "\nwait: `codeloupe job wait ${submission.job.id}` as a background Bash task - one notification when it ends",
            )
            is Submission.Refused -> result(
                "not started - the policy hook answered ${submission.decision.verdict.name.lowercase()}: ${submission.decision.reason}",
                isError = true,
            )
        }
    }

    private fun status(id: String?): CallToolResult {
        if (id == null) return result(jobs.list(10).joinToString("\n") { JobReport.line(it, jobs.ahead(it)) }.ifEmpty { "no jobs" })
        val chain = jobs.chain(id).ifEmpty { return result("no such job: $id", isError = true) }
        return result(if (chain.last().status.terminal) JobReport.text(chain) else JobReport.line(chain.last(), jobs.ahead(chain.last())))
    }

    private fun cancel(id: String): CallToolResult {
        val job = jobs.cancel(id) ?: return result("no such job: $id", isError = true)
        return result("cancelling ${JobReport.line(job)}")
    }

    private fun string(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun strings(args: JsonObject, key: String): List<String> = when (val value = args[key]) {
        null -> emptyList()
        is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("$key must be an array of strings") }
        else -> throw IllegalArgumentException("$key must be an array of strings")
    }

    private fun result(text: String, isError: Boolean = false) = CallToolResult(content = listOf(TextContent(text)), isError = isError)

    private companion object {
        const val NAME = "job"
        const val DESCRIPTION = "Run a long command (tests, builds, deploys) in the CodeLoupe daemon, detached from this session, " +
            "instead of waiting on it: action=start returns at once; then end your turn and run `codeloupe job wait <id>` as a background " +
            "Bash task (one notification, compact result). slot queues on a named resource (e.g. gradle-test). then/onFailure: typed " +
            "follow-ups — `job[@slot]:<command>`, `notify[:message]`, `webhook:<url>`, optionally prefixed `failed==0 ? `. " +
            "Every command passes the workspace policy hook first. action=status [id], action=cancel id."

        val PROPERTIES: JsonObject = buildJsonObject {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { listOf("start", "status", "cancel").forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("command") {
                put("type", "array")
                put("description", "Program and arguments (argv); no shell. Use [\"bash\", \"-c\", \"…\"] for pipes and &&.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("cwd") {
                put("type", "string")
                put("description", "Absolute directory to run in")
            }
            putJsonObject("slot") {
                put("type", "string")
                put("description", "Named resource to hold while running; waits in the daemon when busy")
            }
            putJsonObject("then") {
                put("type", "array")
                put("description", "Steps after success, in order")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("onFailure") {
                put("type", "array")
                put("description", "Steps after a failure, in order")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("wake") {
                put("type", "string")
                putJsonArray("enum") { listOf("always", "failure", "never").forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("env") {
                put("type", "object")
                put("description", "Extra environment variables (never stored)")
            }
            putJsonObject("tag") {
                put("type", "string")
                put("description", "Free label carried into events, e.g. the session or task")
            }
            putJsonObject("id") { put("type", "string") }
        }
    }
}
