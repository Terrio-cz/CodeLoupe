package codeloupe.hooks

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hook endpoint of a real daemon over a real repository, and the plugin script that reaches it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HooksDaemonTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val config = Config(TestRepos.tmpDir("hooks-home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null)
    private val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
    private val big = repo.resolve("big/src/main/kotlin/com/example/big/Big.kt")
    private val daemon = Daemon.start(config)
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun post(body: String, path: String = "/hook", header: Boolean = true): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("content-type", "application/json")
        if (header) request.header(CodeLoupe.HEADER, "1")
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun preToolUse(cwd: Path, tool: String, key: String, value: String, session: String = "s") = buildJsonObject {
        put("hook_event_name", "PreToolUse")
        put("session_id", session)
        put("cwd", cwd.toString())
        put("tool_name", tool)
        put("tool_input", buildJsonObject { put(key, value) })
    }.toString()

    private fun index() {
        val find = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/find")).header(CodeLoupe.HEADER, "1")
            .POST(HttpRequest.BodyPublishers.ofString("""{"root":"${repo.toString().replace("\\", "/")}","q":"Big"}""")).build()
        assertContains(http.send(find, HttpResponse.BodyHandlers.ofString()).body(), "class Big")
    }

    private fun config(text: String) = Files.writeString(config.home.resolve("config.json"), text)

    @Test
    @Order(1)
    fun `an indexed repository is answered, an unknown one and a bad call are not`() {
        // Nothing is indexed yet: the hook must not start a build, nor advise.
        assertEquals(204, post(preToolUse(repo, "Read", "file_path", big.toString())).statusCode())
        assertTrue(daemon.status().repos.isEmpty(), "the hook does not index a repository")

        index()
        val read = post(preToolUse(repo, "Read", "file_path", big.toString(), session = "r"))
        assertEquals(200, read.statusCode())
        val context = Json.parseToJsonElement(read.body()).jsonObject["hookSpecificOutput"]!!.jsonObject["additionalContext"]!!.jsonPrimitive.content
        assertContains(context, """outline target="big/src/main/kotlin/com/example/big/Big.kt"""")

        val search = post(preToolUse(repo, "Bash", "command", "rg -w Big src", session = "b"))
        assertContains(search.body(), """usages name=\"Big\"""")

        val elsewhere = TestRepos.fixtureRepo("kotlin/sample")
        assertEquals(204, post(preToolUse(elsewhere, "Bash", "command", "rg -w Big src", session = "e")).statusCode(), "a repository the daemon has not indexed")
        assertEquals(204, post("{not json").statusCode())
        assertEquals(204, post("{}").statusCode())
        assertEquals(403, post("{}", header = false).statusCode())
        val stats = daemon.status().hooks
        assertTrue(stats.advised >= 2 && stats.calls >= 5, stats.toString())
    }

    @Test
    @Order(2)
    fun `the settings in config json apply to the next call`() {
        index()
        config("""{"hooks":{"steer":{"mode":"redirect"}}}""")
        val denied = post(preToolUse(repo, "Bash", "command", "grep -n total ${repo.resolve("src/main/kotlin/com/example/shop/Constructs.kt")}", session = "deny"))
        assertContains(denied.body(), "\"permissionDecision\":\"deny\"")
        config("""{"hooks":{"enabled":false}}""")
        assertEquals(204, post(preToolUse(repo, "Bash", "command", "rg -w Other1", session = "off")).statusCode())
        config("not json")
        assertEquals(200, post(preToolUse(repo, "Bash", "command", "rg -w Other2", session = "broken")).statusCode(), "an unreadable file means the defaults")
        Files.deleteIfExists(config.home.resolve("config.json"))
    }

    // The Git Bash Claude Code uses on Windows; a `bash` on the PATH there may be WSL's, which reads another path syntax.
    private fun bash(): String? {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val candidates = if (windows) listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe").filter { Files.exists(Path.of(it)) } else listOf("bash")
        return candidates.firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "-c", "true").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        }
    }

    private fun script(bash: String, env: Map<String, String>, stdin: String): Pair<Int, String> {
        val file = Path.of(System.getProperty("codeloupe.projectDir"), "plugin", "hooks", "hook.sh").toString().replace("\\", "/")
        val process = ProcessBuilder(bash, file).redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment().putAll(env) }.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "the script finishes")
        return process.exitValue() to out
    }

    @Test
    @Order(3)
    fun `the plugin script prints the reply and fails open`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        bash!!
        index()
        val call = preToolUse(repo, "Read", "file_path", big.toString(), session = "script")
        val (code, out) = script(bash, mapOf("CODELOUPE_PORT" to port.toString()), call)
        assertEquals(0, code)
        assertContains(out, "additionalContext")

        // The port from daemon.json of the daemon's directory, as the plugin finds it without CODELOUPE_PORT.
        val (viaFile, outFile) = script(bash, mapOf("CODELOUPE_HOME" to config.home.toString(), "CODELOUPE_PORT" to ""), preToolUse(repo, "Read", "file_path", big.toString(), session = "script2"))
        assertEquals(0, viaFile)
        assertContains(outFile, "additionalContext")

        val closed = ServerSocket(0).use { it.localPort }
        val started = System.nanoTime()
        assertEquals(0 to "", script(bash, mapOf("CODELOUPE_PORT" to closed.toString()), call), "nothing listens")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals(0 to "", script(bash, mapOf("CODELOUPE_HOME" to TestRepos.tmpDir("nodaemon").toString(), "CODELOUPE_PORT" to ""), call), "no daemon.json")
        assertEquals(0 to "", script(bash, mapOf("CODELOUPE_PORT" to port.toString(), "CODELOUPE_HOOKS" to "off"), call), "switched off")
        assertEquals(0 to "", script(bash, mapOf("CODELOUPE_PORT" to port.toString()), "garbage"), "malformed input")
    }
}
