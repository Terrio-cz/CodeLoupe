package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.config.Config
import codeloupe.config.PortPolicy
import codeloupe.daemon.ToolOutcome
import codeloupe.platform.DetachedStart
import codeloupe.platform.JavaProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** CLI side: reach the daemon, starting it when it is not running. */
class DaemonClient(private val config: Config) {
    private val http = LocalHttp("http://127.0.0.1:${config.port}")

    /** The daemon's `/status` JSON, or null when nothing (or something else) answers. */
    fun status(timeoutMs: Long = 800): JsonObject? = runCatching {
        val body = JsonFormat.json.parseToJsonElement(http.request("GET", "/status", readTimeoutMs = timeoutMs.toInt()).body).jsonObject
        body.takeIf { it["name"]?.jsonPrimitive?.content == CodeLoupe.NAME }
    }.getOrNull()

    fun ensureDaemon(): JsonObject {
        status()?.let { return checkedHome(it) }
        PortPolicy.refusal(config)?.let { throw IllegalStateException(it) }
        Files.createDirectories(config.home)
        // Its own home as working directory: the daemon outlives the CLI and must not hold the user's directory.
        val args = listOf("daemon", "--detached", "--home", config.home.toString(), "--port", config.port.toString()) +
            config.defaultRoot?.let { listOf("--root", it) }.orEmpty()
        DetachedStart.start(JavaProcess.command(MAIN_CLASS, DaemonJvm.args(parsesHere = config.parseWorkerIdleSeconds <= 0), args), config.home)
        repeat(80) {
            Thread.sleep(100)
            status(300)?.let { return checkedHome(it) }
        }
        throw IllegalStateException("daemon did not start on 127.0.0.1:${config.port}; see ${config.home.resolve("daemon.log")}")
    }

    /**
     * The daemon on our port must be ours: one started with another home has another `config.json` (maybe no policy
     * hook), and nothing is sent to it.
     */
    private fun checkedHome(status: JsonObject): JsonObject {
        val home = status["home"]?.jsonPrimitive?.content ?: return status
        val ours = config.home.toAbsolutePath().normalize().toString()
        val theirs = Path.of(home).toAbsolutePath().normalize().toString()
        if (!theirs.equals(ours, ignoreCase = File.separatorChar == '\\')) {
            throw IllegalStateException("127.0.0.1:${config.port} is served by a daemon with home $theirs, not $ours")
        }
        return status
    }

    fun call(tool: String, args: JsonObject): ToolOutcome {
        ensureDaemon()
        val body = http.request("POST", "/api/$tool", args.toString(), JSON_HEADERS).body
        val json = JsonFormat.json.parseToJsonElement(body).jsonObject
        return json["text"]?.let { JsonFormat.json.decodeFromJsonElement(ToolOutcome.serializer(), json) }
            ?: ToolOutcome(false, json["error"]?.jsonPrimitive?.content ?: body)
    }

    /**
     * A JSON request to the daemon (started if needed). [timeout] null waits as long as the daemon takes: long polls.
     * Answers the status code and the body (`{"error": …}` when it was not JSON).
     */
    fun send(method: String, path: String, body: JsonObject? = null, timeout: Duration? = Duration.ofSeconds(60)): Pair<Int, JsonObject> {
        ensureDaemon()
        val response = http.request(method, path, body?.toString(), JSON_HEADERS, readTimeoutMs = timeout?.toMillis()?.toInt() ?: 0)
        val json = runCatching { JsonFormat.json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: JsonObject(mapOf("error" to JsonPrimitive(response.body.take(300))))
        return response.status to json
    }

    /** True when a running daemon was asked to stop; the refusal text when it has jobs and [force] is false. */
    fun shutdown(force: Boolean = false): Boolean {
        if (status() == null) return false
        val response = runCatching {
            http.request("POST", "/shutdown${if (force) "?force=1" else ""}", headers = mapOf(CodeLoupe.HEADER to "1"))
        }.getOrNull()
        if (response?.status == 409) {
            throw IllegalStateException(runCatching { JsonFormat.json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull() ?: "daemon has jobs")
        }
        repeat(50) {
            if (status(200) == null) return true
            Thread.sleep(100)
        }
        return true
    }

    private companion object {
        const val MAIN_CLASS = "codeloupe.MainKt"
        val JSON_HEADERS = mapOf("content-type" to "application/json", CodeLoupe.HEADER to "1")
    }
}
