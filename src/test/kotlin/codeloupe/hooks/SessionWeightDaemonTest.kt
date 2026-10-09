package codeloupe.hooks

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The session-weight endpoint and the plugin script around it, against a real daemon. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionWeightDaemonTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val config = Config(TestRepos.tmpDir("weight-home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null)
    private val daemon = Daemon.start(config)
    private val http = HttpClient.newHttpClient()
    private val transcript = SyntheticTranscript(TestRepos.tmpDir("weight-t").resolve("s.jsonl"))

    @AfterAll
    fun stop() = daemon.stop()

    private fun get(path: String, header: Boolean = true): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/session-weight?path=" + URLEncoder.encode(path, Charsets.UTF_8)))
        if (header) request.header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token)
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `the endpoint answers with the weight of a transcript and refuses what is not one`() {
        transcript.turn(40_000, "Read", resultChars = 80_000)
        transcript.turn(160_000, "Bash", resultChars = 100)
        val answer = get(transcript.path.toString())
        assertEquals(200, answer.statusCode())
        val json = Json.parseToJsonElement(answer.body()).jsonObject
        assertEquals("160000", json["contextTokens"]!!.jsonPrimitive.content)
        assertEquals("1", json["level"]!!.jsonPrimitive.content)
        assertContains(answer.body(), "\"tool\":\"Read\"")
        assertEquals(404, get(transcript.path.resolveSibling("none.jsonl").toString()).statusCode())
        val text = Files.writeString(transcript.path.resolveSibling("notes.txt"), "x")
        assertEquals(404, get(text.toString()).statusCode(), "only transcripts")
        assertEquals(403, get(transcript.path.toString(), header = false).statusCode())
    }

    private fun bash(): String? {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val candidates = if (windows) listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe").filter { Files.exists(Path.of(it)) } else listOf("bash")
        return candidates.firstOrNull { runCatching { ProcessBuilder(it, "-c", "true").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
    }

    @Test
    fun `the plugin script prints the advisory once for a size`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val script = Path.of(System.getProperty("codeloupe.projectDir"), "plugin", "hooks", "hook.sh").toString().replace("\\", "/")
        val own = SyntheticTranscript(transcript.path.resolveSibling("own.jsonl"))
        own.turn(200_000, "Read", resultChars = 120_000)
        own.turn(210_000)
        fun run(event: String): String {
            val input = """{"hook_event_name":"$event","session_id":"script-session","transcript_path":"${own.path.toString().replace("\\", "/")}"}"""
            val process = ProcessBuilder(bash!!, script).redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["CODELOUPE_PORT"] = port.toString(); environment()["CODELOUPE_HOME"] = config.home.toString() }.start()
            process.outputStream.use { it.write(input.toByteArray()) }
            val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            process.waitFor(30, TimeUnit.SECONDS)
            assertEquals(0, process.exitValue())
            return out
        }
        val first = run("Stop")
        assertContains(first, "systemMessage")
        assertContains(first, "~210k tokens")
        assertEquals("", run("UserPromptSubmit"))
        assertEquals("", run("Stop"))
    }
}
