package codeloupe.jobs

import codeloupe.jobs.PolicyDecision.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The workspace policy for commands the daemon runs. A daemon-run command never passes the agent host's own guard
 * hooks, so it is fed to the configured `policyHook` exactly as Claude Code feeds a PreToolUse hook for a Bash call,
 * and only an allow starts it. The hook runs in the daemon's environment: a caller cannot point it elsewhere with
 * variables of its own. Fails closed: a crash, a timeout or output it cannot read is a deny.
 */
class PolicyHook(private val command: List<String>?, private val timeoutMs: Long) {
    val configured: Boolean get() = command != null

    fun check(bashCommand: String, cwd: String): PolicyDecision {
        val argv = command ?: return PolicyDecision(Verdict.ALLOW, "no policy hook configured")
        val input = buildJsonObject {
            put("session_id", "codeloupe")
            put("transcript_path", "")
            put("cwd", cwd)
            put("permission_mode", "default")
            put("hook_event_name", "PreToolUse")
            put("tool_name", "Bash")
            putJsonObject("tool_input") {
                put("command", bashCommand)
                put("description", "codeloupe job")
            }
        }
        val dir = Path.of(cwd).takeIf { Files.isDirectory(it) }
        val process = try {
            ProcessBuilder(argv).apply { if (dir != null) directory(dir.toFile()) }.start()
        } catch (e: Exception) {
            return deny("policy hook did not start: ${e.message}")
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val stdout = read(process.inputStream)
        val stderr = read(process.errorStream)
        runCatching { process.outputStream.use { it.write(input.toString().toByteArray(Charsets.UTF_8)) } }
        // The pipes are awaited within the same budget: a grandchild of the hook may hold them open after it exits.
        val out = runCatching {
            if (!process.waitFor(remaining(deadline), TimeUnit.MILLISECONDS)) throw TimeoutException()
            stdout.get(remaining(deadline), TimeUnit.MILLISECONDS) to stderr.get(remaining(deadline), TimeUnit.MILLISECONDS)
        }.getOrElse {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            return deny("policy hook timed out after $timeoutMs ms")
        }
        return decide(process.exitValue(), out.first, out.second)
    }

    private fun read(stream: InputStream): CompletableFuture<String> =
        CompletableFuture<String>().also { result ->
            Thread.ofVirtual().start { result.complete(runCatching { stream.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault("")) }
        }

    private fun remaining(deadline: Long) = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)

    /** Claude Code's reading of a hook's result, minus its leniency: exit 2 or anything unreadable denies. */
    internal fun decide(exit: Int, stdout: String, stderr: String): PolicyDecision {
        if (exit == 2) return deny(firstLine(stderr).ifEmpty { "blocked by the policy hook" })
        if (exit != 0) return deny("policy hook failed (exit $exit): ${firstLine(stderr)}".trimEnd(' ', ':'))
        val text = stdout.trim()
        if (text.isEmpty()) return PolicyDecision(Verdict.ALLOW, "policy hook: no objection")
        val json = (runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: runCatching { Json.parseToJsonElement(text.lines().last { it.isNotBlank() }).jsonObject }.getOrNull())
            ?: return deny("policy hook output is not JSON: ${text.take(120)}")
        val specific = json["hookSpecificOutput"] as? JsonObject
        val reason = string(specific, "permissionDecisionReason") ?: string(json, "reason") ?: ""
        if (string(json, "continue") == "false") return deny(string(json, "stopReason") ?: reason.ifEmpty { "policy hook stopped it" })
        return when (string(specific, "permissionDecision") ?: legacy(string(json, "decision"))) {
            "allow" -> PolicyDecision(Verdict.ALLOW, reason.ifEmpty { "allowed by the policy hook" })
            "ask" -> PolicyDecision(Verdict.ASK, reason.ifEmpty { "the policy hook wants a person to confirm" })
            "deny" -> deny(reason.ifEmpty { "denied by the policy hook" })
            null -> PolicyDecision(Verdict.ALLOW, "policy hook: no objection")
            else -> deny("policy hook answered something unknown")
        }
    }

    private fun legacy(decision: String?) = when (decision) {
        "approve" -> "allow"
        "block" -> "deny"
        else -> decision
    }

    private fun string(json: JsonObject?, key: String): String? = (json?.get(key) as? JsonPrimitive)?.content

    private fun firstLine(text: String) = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(300).orEmpty()

    private fun deny(reason: String) = PolicyDecision(Verdict.DENY, reason)
}
