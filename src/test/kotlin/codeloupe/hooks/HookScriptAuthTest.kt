package codeloupe.hooks

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.daemon.DaemonToken
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `hook.sh` and `mcp-headers.sh` hand the daemon token only to a daemon that proves it holds it, and tell a stranger on the port nothing. */
class HookScriptAuthTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val reply = """{"systemMessage":"map"}"""

    private fun bash(): String? {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val candidates = if (windows) listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe").filter { Files.exists(Path.of(it)) } else listOf("bash")
        return candidates.firstOrNull { runCatching { ProcessBuilder(it, "-c", "true").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
    }

    private fun home(withToken: Boolean = true): Path = TestRepos.tmpDir("hook-auth-home").also {
        if (withToken) Files.writeString(it.resolve(DaemonToken.FILE), "${CodeLoupe.TOKEN_HEADER}: $token\n")
    }

    /** What reached the fake daemon: every request's headers and body. */
    private class Seen(val path: String, val headers: Map<String, List<String>>, val body: String)

    /** A fake daemon; [proof] says what it answers to a nonce on `/status`. */
    private fun fake(proof: (String) -> String?): Triple<HttpServer, Int, CopyOnWriteArrayList<Seen>> {
        val seen = CopyOnWriteArrayList<Seen>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            seen += Seen(exchange.requestURI.path, exchange.requestHeaders.toMap(), body)
            val answer = if (exchange.requestURI.path == "/status") {
                proof(exchange.requestHeaders.getFirst(CodeLoupe.NONCE_HEADER).orEmpty())?.let { exchange.responseHeaders.add(CodeLoupe.PROOF_HEADER, it) }
                """{"name":"codeloupe"}"""
            } else {
                reply
            }.toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        return Triple(server, server.address.port, seen)
    }

    private fun run(bash: String, script: String, port: Int, home: Path, stdin: String): String {
        val file = Path.of(System.getProperty("codeloupe.projectDir"), "plugin", "hooks", script).toString().replace("\\", "/")
        val process = ProcessBuilder(bash, file).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
            environment()["CODELOUPE_PORT"] = port.toString()
            environment()["CODELOUPE_HOME"] = home.toString()
            environment()["CODELOUPE_BIN"] = "/nonexistent/codeloupe"
        }.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        return out
    }

    private val event = """{"hook_event_name":"UserPromptSubmit","prompt":"secret plan"}"""

    private fun hasToken(seen: List<Seen>) = seen.any { s -> s.body.contains(token) || s.headers.entries.any { (k, v) -> k.equals(CodeLoupe.TOKEN_HEADER, true) || v.any { it.contains(token) } } }

    @Test
    fun `the hook proves the daemon first, then sends the token and the event`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val (server, port, seen) = fake { DaemonToken.proof(token, it) }
        try {
            assertEquals(reply, run(bash!!, "hook.sh", port, home(), event))
            assertEquals(listOf("/status", "/hook"), seen.map { it.path })
            val hook = seen.last()
            assertEquals(token, hook.headers.entries.first { it.key.equals(CodeLoupe.TOKEN_HEADER, true) }.value.single())
            assertTrue(hook.body.contains("secret plan"))
            assertFalse(seen.first().headers.keys.any { it.equals(CodeLoupe.TOKEN_HEADER, true) }, "the token does not go with the challenge")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a process that cannot prove it holds the token gets neither the token nor the event`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        for (proof in listOf<(String) -> String?>({ null }, { "0".repeat(64) }, { DaemonToken.proof("f".repeat(64), it) })) {
            val (server, port, seen) = fake(proof)
            try {
                assertEquals("", run(bash!!, "hook.sh", port, home(), event))
                assertEquals(listOf("/status"), seen.map { it.path }, "only the challenge went")
                assertFalse(hasToken(seen), "the token reached a stranger")
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `without a token file the hook talks to the port as before`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val (server, port, seen) = fake { null }
        try {
            assertEquals(reply, run(bash!!, "hook.sh", port, home(withToken = false), event))
            assertEquals(listOf("/hook"), seen.map { it.path })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an unusable token file stops the hook instead of sending a prompt`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val (server, port, seen) = fake { null }
        try {
            val dir = home(withToken = false)
            Files.writeString(dir.resolve(DaemonToken.FILE), "garbage")
            assertEquals("", run(bash!!, "hook.sh", port, dir, event))
            assertTrue(seen.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the headers helper prints the token for a daemon that proved itself and the plain header for any other`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val (server, port, seen) = fake { DaemonToken.proof(token, it) }
        try {
            assertEquals("""{"x-codeloupe":"1","x-codeloupe-token":"$token"}""", run(bash!!, "mcp-headers.sh", port, home(), "").trim())
        } finally {
            server.stop(0)
        }
        val (rogue, roguePort, rogueSeen) = fake { null }
        try {
            assertEquals("""{"x-codeloupe":"1"}""", run(bash, "mcp-headers.sh", roguePort, home(), "").trim())
            assertEquals("""{"x-codeloupe":"1"}""", run(bash, "mcp-headers.sh", roguePort, home(withToken = false), "").trim())
            assertFalse(hasToken(rogueSeen))
            assertTrue(seen.isNotEmpty())
        } finally {
            rogue.stop(0)
        }
    }
}
