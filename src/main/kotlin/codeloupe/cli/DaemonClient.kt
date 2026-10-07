package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.config.Config
import codeloupe.daemon.ToolOutcome
import codeloupe.platform.JavaProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** CLI side: reach the daemon, starting it when it is not running. */
class DaemonClient(private val config: Config) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build()
    private val base = "http://127.0.0.1:${config.port}"

    /** The daemon's `/status` JSON, or null when nothing (or something else) answers. */
    fun status(timeoutMs: Long = 800): JsonObject? = runCatching {
        val request = HttpRequest.newBuilder(URI("$base/status")).timeout(Duration.ofMillis(timeoutMs)).GET().build()
        val body = JsonFormat.json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        body.takeIf { it["name"]?.jsonPrimitive?.content == CodeLoupe.NAME }
    }.getOrNull()

    fun ensureDaemon(): JsonObject {
        status()?.let { return it }
        ProcessBuilder(JavaProcess.command(MAIN_CLASS, DaemonJvm.args(config.home), listOf("daemon")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .outputStream.close()
        repeat(80) {
            Thread.sleep(100)
            status(300)?.let { return it }
        }
        throw IllegalStateException("daemon did not start on 127.0.0.1:${config.port}; see ${config.home.resolve("daemon.log")}")
    }

    fun call(tool: String, args: JsonObject): ToolOutcome {
        ensureDaemon()
        val request = HttpRequest.newBuilder(URI("$base/api/$tool"))
            .header("content-type", "application/json")
            .header(CodeLoupe.HEADER, "1")
            .POST(HttpRequest.BodyPublishers.ofString(args.toString()))
            .build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        val json = JsonFormat.json.parseToJsonElement(body).jsonObject
        return json["text"]?.let { JsonFormat.json.decodeFromJsonElement(ToolOutcome.serializer(), json) }
            ?: ToolOutcome(false, json["error"]?.jsonPrimitive?.content ?: body)
    }

    /** True when a running daemon was asked to stop. */
    fun shutdown(): Boolean {
        if (status() == null) return false
        val request = HttpRequest.newBuilder(URI("$base/shutdown")).header(CodeLoupe.HEADER, "1").POST(HttpRequest.BodyPublishers.noBody()).build()
        runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()) }
        repeat(50) {
            if (status(200) == null) return true
            Thread.sleep(100)
        }
        return true
    }

    private companion object {
        const val MAIN_CLASS = "codeloupe.MainKt"
    }
}
