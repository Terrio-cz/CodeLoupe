package codeloupe.hooks

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How long `plugin/hooks/hook.sh` waits for a slow daemon: a tool call 2 s, only a real session start 10 s. */
class HookScriptWaitTest {
    private fun bash(): String? {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val candidates = if (windows) listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe").filter { Files.exists(Path.of(it)) } else listOf("bash")
        return candidates.firstOrNull { candidate -> runCatching { ProcessBuilder(candidate, "-c", "true").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
    }

    private fun slowDaemon(delayMs: Long): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { exchange ->
            exchange.requestBody.readAllBytes()
            Thread.sleep(delayMs)
            val reply = REPLY.toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        return server to server.address.port
    }

    private fun run(bash: String, port: Int, body: String): Pair<String, Duration> {
        val file = Path.of(System.getProperty("codeloupe.projectDir"), "plugin", "hooks", "hook.sh").toString().replace("\\", "/")
        val started = System.nanoTime()
        val process = ProcessBuilder(bash, file).redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["CODELOUPE_PORT"] = port.toString() }.start()
        process.outputStream.use { it.write(body.toByteArray()) }
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        return out to Duration.ofNanos(System.nanoTime() - started)
    }

    @Test
    fun `a tool call whose text mentions SessionStart is not given a session start's patience`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val (server, port) = slowDaemon(6_000)
        try {
            val body = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"grep -r SessionStart src"}}"""
            val (out, took) = run(bash!!, port, body)
            assertEquals("", out)
            assertTrue(took < Duration.ofSeconds(5), "waited $took for a tool call")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a session start waits for the repository map`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val (server, port) = slowDaemon(3_000)
        try {
            var result = run(bash!!, port, """{"hook_event_name":"SessionStart","source":"startup"}""")
            repeat(2) { if (result.first.isEmpty()) result = run(bash, port, """{"hook_event_name":"SessionStart","source":"startup"}""") }
            val (out, took) = result
            assertEquals(REPLY, out)
            assertTrue(took >= Duration.ofSeconds(2), "the slow answer was waited for: $took")
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        // One of the shapes the script lets through to Claude Code.
        const val REPLY = """{"systemMessage":"map"}"""
    }
}
