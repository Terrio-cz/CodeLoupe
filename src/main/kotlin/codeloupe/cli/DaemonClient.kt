package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.aot.AotCaches
import codeloupe.JsonFormat
import codeloupe.config.Config
import codeloupe.config.PortPolicy
import codeloupe.daemon.DaemonInfo
import codeloupe.daemon.DaemonToken
import codeloupe.daemon.ToolOutcome
import codeloupe.platform.DetachedStart
import codeloupe.platform.JavaProcess
import codeloupe.platform.PathCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** CLI side: reach the daemon, starting it when it is not running. */
class DaemonClient(private val config: Config, private val launcher: (() -> Unit)? = null) {
    private val http = LocalHttp("http://127.0.0.1:${config.port}")

    /** The daemon's token, once the daemon on our port has shown that it holds it: it is never sent to anything that has not. */
    private var token: String? = null

    /** True once this client started the daemon process, as opposed to finding it running: the first answers of a new daemon get a second try. */
    private var startedHere = false

    /** The daemon's `/status` JSON, or null when nothing (or something else) answers. */
    fun status(timeoutMs: Long = 800): JsonObject? = runCatching {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val reply = http.request("GET", "/status", headers = mapOf(CodeLoupe.NONCE_HEADER to nonce), readTimeoutMs = timeoutMs.toInt())
        val body = JsonFormat.json.parseToJsonElement(reply.body).jsonObject
        body.takeIf { it["name"]?.jsonPrimitive?.content == CodeLoupe.NAME }?.also { token = provenToken(reply, nonce) }
    }.getOrNull()

    /** The token in our home when the answer carries the proof only a holder of it can give; null for a daemon from before the token existed, or any other. */
    private fun provenToken(reply: LocalHttp.Reply, nonce: String): String? =
        DaemonToken.read(config.home)?.takeIf { reply.header(CodeLoupe.PROOF_HEADER) == DaemonToken.proof(it, nonce) }

    private fun headers(): Map<String, String> = JSON_HEADERS + listOfNotNull(token?.let { CodeLoupe.TOKEN_HEADER to it })

    /** The token, when the daemon (started if need be) has proved it holds it; null for a daemon that predates the token. */
    fun trustedToken(): String? {
        ensureDaemon()
        return token
    }

    fun ensureDaemon(): JsonObject {
        status()?.let { return named(it) }
        PortPolicy.refusal(config)?.let { throw IllegalStateException(it) }
        Files.createDirectories(config.home)
        (launcher ?: ::launch)()
        startedHere = true
        repeat(80) {
            Thread.sleep(100)
            status(300)?.let { return named(it) }
        }
        throw IllegalStateException("daemon did not start on 127.0.0.1:${config.port}; see ${config.home.resolve("daemon.log")}")
    }

    private fun launch() {
        // Its own home as working directory: the daemon outlives the CLI and must not hold the user's directory.
        val args = listOf("daemon", "--detached", "--home", config.home.toString(), "--port", config.port.toString()) +
            config.defaultRoot?.let { listOf("--root", it) }.orEmpty()
        val jvm = DaemonJvm.args(parsesHere = config.parseWorkerIdleSeconds <= 0) + AotCaches.fromProperty()?.daemonFlags().orEmpty()
        DetachedStart.start(JavaProcess.command(MAIN_CLASS, jvm, args), config.home)
    }

    /**
     * The daemon on our port must be the one `daemon.json` names. A file that is stale or being rewritten by a daemon that has just started
     * gets a moment; a process that stays another one is not trusted with a request.
     */
    private fun named(first: JsonObject): JsonObject {
        var status = first
        repeat(NAMED_TRIES) {
            checkedHome(status)
            val named = namedPid()
            val pid = status["pid"]?.jsonPrimitive?.content?.toLongOrNull()
            if (named == null || named == pid) return status
            Thread.sleep(NAMED_WAIT_MS)
            status = status(300) ?: throw IllegalStateException("the daemon on 127.0.0.1:${config.port} stopped answering")
        }
        throw IllegalStateException("127.0.0.1:${config.port} is answered by process ${status["pid"]?.jsonPrimitive?.content}, but daemon.json names ${namedPid()}: not trusting it (stop it, or delete ${config.home.resolve("daemon.json")} if the daemon it names is gone)")
    }

    private fun namedPid(): Long? = runCatching {
        JsonFormat.json.decodeFromString(DaemonInfo.serializer(), Files.readString(config.home.resolve("daemon.json"))).pid
    }.getOrNull()

    /**
     * The daemon on our port must be ours: one started with another home has another `config.json` (maybe no policy
     * hook), and nothing is sent to it.
     */
    private fun checkedHome(status: JsonObject): JsonObject {
        val home = status["home"]?.jsonPrimitive?.content ?: return status
        val ours = config.home.toAbsolutePath().normalize().toString()
        val theirs = Path.of(home).toAbsolutePath().normalize().toString()
        if (!theirs.equals(ours, ignoreCase = PathCase.insensitive)) {
            throw IllegalStateException("127.0.0.1:${config.port} is served by a daemon with home $theirs, not $ours")
        }
        return status
    }

    fun call(tool: String, args: JsonObject): ToolOutcome {
        ensureDaemon()
        val reply = exchange("POST", "/api/$tool", args.toString(), bodyRequired = true)
        if (reply.body.isEmpty()) throw IllegalStateException(emptyAnswer(reply))
        val json = JsonFormat.json.parseToJsonElement(reply.body).jsonObject
        return json["text"]?.let { JsonFormat.json.decodeFromJsonElement(ToolOutcome.serializer(), json) }
            ?: ToolOutcome(false, json["error"]?.jsonPrimitive?.content ?: reply.body)
    }

    /**
     * One request to the daemon that [ensureDaemon] has just vouched for. An answer without a body where one is due (an error status always;
     * any status when [bodyRequired]) from a daemon this client started is asked for once more after the daemon is checked again: it may
     * have answered while still settling, and `status()` fetches the token again. The reply of the second try is returned as it is.
     */
    private fun exchange(method: String, path: String, body: String? = null, readTimeoutMs: Int = 0, bodyRequired: Boolean = false): LocalHttp.Reply {
        val reply = http.request(method, path, body, headers(), readTimeoutMs = readTimeoutMs)
        if (reply.body.isNotEmpty() || !(bodyRequired || reply.status >= 400) || !startedHere) return reply
        startedHere = false
        ensureDaemon()
        return http.request(method, path, body, headers(), readTimeoutMs = readTimeoutMs)
    }

    /**
     * Why an answer has no body. A 401 is the usual reason: the JDK keeps no body of it for a request sent as a stream, so the daemon's own
     * words are gone, and the cause is a token in `daemon.token` that is not the one the daemon holds.
     */
    private fun emptyAnswer(reply: LocalHttp.Reply): String {
        val where = "the daemon on 127.0.0.1:${config.port}"
        return when (reply.status) {
            401 -> "$where refused the request (HTTP 401, no body): the token in ${config.home.resolve(DaemonToken.FILE)} is not the one it holds, or it needs one; " +
                "end that daemon (codeloupe stop, or its process) and run the call again"
            403 -> "$where refused the request (HTTP 403, no body)"
            else -> "$where answered HTTP ${reply.status} with an empty body: it closed the connection before it had sent the answer; see ${config.home.resolve("daemon.log")}"
        }
    }

    /**
     * A JSON request to the daemon (started if needed). [timeout] null waits as long as the daemon takes: long polls.
     * Answers the status code and the body (`{"error": …}` when it was not JSON).
     */
    fun send(method: String, path: String, body: JsonObject? = null, timeout: Duration? = Duration.ofSeconds(60)): Pair<Int, JsonObject> {
        ensureDaemon()
        val response = exchange(method, path, body?.toString(), timeout?.toMillis()?.toInt() ?: 0)
        val text = if (response.body.isEmpty() && response.status >= 400) emptyAnswer(response) else response.body
        val json = runCatching { JsonFormat.json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: JsonObject(mapOf("error" to JsonPrimitive(text.take(300))))
        return response.status to json
    }

    /** True when a running daemon was asked to stop; the refusal text when it has jobs and [force] is false. */
    fun shutdown(force: Boolean = false): Boolean {
        if (status() == null) return false
        val response = runCatching {
            http.request("POST", "/shutdown${if (force) "?force=1" else ""}", headers = headers())
        }.getOrNull()
        if (response?.status == 409 || response?.status == 401) {
            val said = runCatching { JsonFormat.json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw IllegalStateException(said ?: if (response.body.isEmpty()) emptyAnswer(response) else "daemon has jobs")
        }
        repeat(50) {
            if (status(200) == null) return true
            Thread.sleep(100)
        }
        return true
    }

    private companion object {
        const val MAIN_CLASS = "codeloupe.MainKt"
        const val NAMED_TRIES = 10
        const val NAMED_WAIT_MS = 200L
        val JSON_HEADERS = mapOf("content-type" to "application/json", CodeLoupe.HEADER to "1")
    }
}
