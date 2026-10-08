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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The session-start hook of a real daemon over a real repository on a task branch. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SessionStartDaemonTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val config = Config(TestRepos.tmpDir("start-home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null)
    private val repo = TestRepos.fixtureRepo("kotlin/sample", TestRepos.SAMPLE_WITH_BIG)
    private val daemon = Daemon.start(config)
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun post(body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/hook")).header("content-type", "application/json").header(CodeLoupe.HEADER, "1").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun start(cwd: Path, source: String = "startup") = buildJsonObject {
        put("hook_event_name", "SessionStart")
        put("session_id", "s-$source")
        put("cwd", cwd.toString())
        put("source", source)
    }.toString()

    private fun context(response: HttpResponse<String>): String =
        Json.parseToJsonElement(response.body()).jsonObject["hookSpecificOutput"]!!.jsonObject["additionalContext"]!!.jsonPrimitive.content

    private fun index() {
        val find = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/find")).header(CodeLoupe.HEADER, "1")
            .POST(HttpRequest.BodyPublishers.ofString("""{"root":"${repo.toString().replace("\\", "/")}","q":"Big"}""")).build()
        assertContains(http.send(find, HttpResponse.BodyHandlers.ofString()).body(), "class Big")
    }

    private fun config(text: String) = Files.writeString(config.home.resolve("config.json"), text)

    @Test
    @Order(1)
    fun `a repository the daemon has not indexed gets nothing, and nothing is built for it`() {
        assertEquals(204, post(start(repo)).statusCode())
        assertTrue(daemon.status().repos.isEmpty())
        assertEquals(204, post(start(TestRepos.tmpDir("not-git"))).statusCode())
    }

    @Test
    @Order(2)
    fun `a fresh session gets the state of its worktree and the map within the budget`() {
        index()
        config("""{"hooks":{"sessionStart":{"map":true}}}""")
        TestRepos.git(repo, "checkout", "-q", "-b", "TER-5-order-total")
        Files.writeString(repo.resolve("big/src/main/kotlin/com/example/big/Big.kt"), Files.readString(repo.resolve("big/src/main/kotlin/com/example/big/Big.kt")).replace("fun m3(x: Int): Int {", "fun m3(x: Long): Long {"))
        val text = context(post(start(repo)))
        assertContains(text, "branch TER-5-order-total (task TER-5), default branch")
        assertContains(text, "changes vs")
        assertContains(text, "map of")
        assertContains(text, "Big.kt")
        assertContains(text, "Orient with outline")
        val budget = 1_200
        assertTrue(text.length <= budget * SessionContext.CHARS_PER_TOKEN, "${text.length} chars for $budget tokens")
        assertFalse(text.lines().any { it.startsWith("      ") }, "no caller lines")
        assertEquals(1L, daemon.status().hooks.sessions)
    }

    @Test
    @Order(3)
    fun `without the map setting a session gets the state of its worktree alone`() {
        Files.deleteIfExists(config.home.resolve("config.json"))
        val text = context(post(start(repo)))
        assertContains(text, "branch TER-5-order-total (task TER-5)")
        assertFalse("map of" in text)
        config("""{"hooks":{"sessionStart":{"map":true}}}""")
    }

    @Test
    @Order(4)
    fun `a resumed or compacted session gets the state alone`() {
        listOf("resume", "compact").forEach { source ->
            val text = context(post(start(repo, source)))
            assertContains(text, "branch TER-5-order-total")
            assertFalse("map of" in text, source)
        }
    }

    @Test
    @Order(5)
    fun `the budget and the switches are read on every call`() {
        config("""{"hooks":{"sessionStart":{"map":true,"budget":300,"changes":false}}}""")
        val small = context(post(start(repo)))
        assertTrue(small.length <= 300 * SessionContext.CHARS_PER_TOKEN, "${small.length} chars")
        assertFalse("changes vs" in small)
        config("""{"hooks":{"sessionStart":{"enabled":false}}}""")
        assertEquals(204, post(start(repo)).statusCode())
        config("""{"hooks":{"enabled":false}}""")
        assertEquals(204, post(start(repo)).statusCode())
        config("not json")
        assertEquals(200, post(start(repo)).statusCode(), "an unreadable file means the defaults")
        Files.deleteIfExists(config.home.resolve("config.json"))
    }

    private fun bash(): String? {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val candidates = if (windows) listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe").filter { Files.exists(Path.of(it)) } else listOf("bash")
        return candidates.firstOrNull { runCatching { ProcessBuilder(it, "-c", "true").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
    }

    @Test
    @Order(6)
    fun `the plugin script starts the session with the context and stays silent without a daemon`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val script = Path.of(System.getProperty("codeloupe.projectDir"), "plugin", "hooks", "start-daemon.sh").toString().replace("\\", "/")
        fun run(env: Map<String, String>): String {
            val process = ProcessBuilder(bash!!, script).redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment().putAll(env) }.start()
            process.outputStream.use { it.write(start(repo).toByteArray()) }
            val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return out
        }
        val out = run(mapOf("CODELOUPE_BIN" to "/nonexistent/codeloupe", "CODELOUPE_PORT" to port.toString()))
        assertContains(out, "CodeLoupe orientation")
        assertEquals("", run(mapOf("CODELOUPE_BIN" to "/nonexistent/codeloupe", "CODELOUPE_PORT" to ServerSocket(0).use { it.localPort }.toString())))
        assertEquals("", run(mapOf("CODELOUPE_BIN" to "/nonexistent/codeloupe", "CODELOUPE_PORT" to port.toString(), "CODELOUPE_HOOKS" to "off")))
    }
}
