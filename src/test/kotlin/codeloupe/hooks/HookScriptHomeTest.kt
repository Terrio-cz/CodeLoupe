package codeloupe.hooks

import codeloupe.TestRepos
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `plugin/hooks/hook.sh` finds the daemon in the home the variable `HOME` names, the home the daemon follows too (CL-165). */
class HookScriptHomeTest {
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")
    private val mac = System.getProperty("os.name").lowercase().startsWith("mac")

    private fun bash(): String? =
        listOf("bash").firstOrNull { candidate -> runCatching { ProcessBuilder(candidate, "-c", "true").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }

    @Test
    fun `the hook reads daemon json under HOME, not under another home`() {
        assumeTrue(!windows, "on Windows the script looks under LOCALAPPDATA")
        val shell = bash()
        assumeTrue(shell != null, "no bash on this machine")
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { exchange ->
            hits.incrementAndGet()
            exchange.requestBody.readAllBytes()
            val reply = """{"systemMessage":"seen"}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val isolated = TestRepos.tmpDir("hook-home")
            val state = if (mac) isolated.resolve("Library/Caches/codeloupe") else isolated.resolve(".cache/codeloupe")
            Files.createDirectories(state)
            Files.writeString(state.resolve("daemon.json"), """{"port":${server.address.port},"pid":1}""")

            assertEquals("""{"systemMessage":"seen"}""", run(shell!!, isolated), "found through HOME")
            assertEquals(1, hits.get())
            assertEquals("", run(shell!!, TestRepos.tmpDir("hook-empty-home")), "an empty home has no daemon")
            assertEquals(1, hits.get())
        } finally {
            server.stop(0)
        }
    }

    private fun run(bash: String, home: Path): String {
        val file = Path.of(System.getProperty("codeloupe.projectDir"), "plugin", "hooks", "hook.sh").toString().replace("\\", "/")
        val process = ProcessBuilder(bash, file).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
            environment().keys.removeAll { it.startsWith("CODELOUPE_") || it == "XDG_CACHE_HOME" }
            environment()["HOME"] = home.toString()
        }.start()
        process.outputStream.use { it.write("""{"hook_event_name":"PreToolUse","tool_name":"Bash"}""".toByteArray()) }
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        return out
    }
}
