package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.daemon.DaemonToken
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The CLI sends the token only to a daemon that has proved it holds it, and does not trust one that `daemon.json` does not name. */
class DaemonClientAuthTest {
    private fun config() = Config(TestRepos.tmpDir("client-home"), ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null)

    @Test
    fun `calls reach the daemon with the token and the headers helper prints it`() {
        val config = config()
        val daemon = Daemon.start(config)
        try {
            val client = DaemonClient(config)
            assertEquals(200, client.send("GET", "/jobs").first)
            assertEquals(daemon.token, client.trustedToken())
            val headers = McpHeadersCommand.headers(config)
            assertEquals("1", headers[CodeLoupe.HEADER].toString().trim('"'))
            assertEquals(daemon.token, headers[CodeLoupe.TOKEN_HEADER].toString().trim('"'))
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `a daemon that cannot prove it holds the token is not sent the token`() {
        val config = config()
        val token = DaemonToken.open(config.home).current()
        for (proof in listOf<String?>(null, "0".repeat(64))) {
            val seen = CopyOnWriteArrayList<Map<String, List<String>>>()
            val rogue = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 0)
            rogue.createContext("/status") { exchange ->
                val body = """{"name":"codeloupe","pid":${ProcessHandle.current().pid()},"home":"${config.home.toString().replace("\\", "\\\\")}"}""".toByteArray()
                proof?.let { exchange.responseHeaders.add(CodeLoupe.PROOF_HEADER, it) }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            rogue.createContext("/jobs") { exchange ->
                seen += exchange.requestHeaders.toMap()
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("{}".toByteArray()) }
            }
            rogue.start()
            try {
                val client = DaemonClient(config)
                assertEquals(200, client.send("GET", "/jobs").first)
                assertNull(client.trustedToken(), "proof=$proof")
                assertEquals(1, seen.size)
                assertTrue(seen.single().keys.none { it.equals(CodeLoupe.TOKEN_HEADER, ignoreCase = true) }, "the token went to a process that did not prove itself: ${seen.single().keys}")
                assertTrue(token.isNotEmpty())
                assertEquals("{\"x-codeloupe\":\"1\"}", McpHeadersCommand.headers(config).toString())
            } finally {
                rogue.stop(0)
            }
        }
    }

    @Test
    fun `a daemon that daemon json does not name is refused, a file that is only stale for a moment is waited out`() {
        val config = config()
        val daemon = Daemon.start(config)
        try {
            val info = config.home.resolve("daemon.json")
            val real = Files.readString(info)
            val pid = ProcessHandle.current().pid()
            Files.writeString(info, real.replace("\"pid\":$pid", "\"pid\":${pid + 7}"))
            val refused = assertFailsWith<IllegalStateException> { DaemonClient(config).ensureDaemon() }
            assertContains(refused.message.orEmpty(), "daemon.json names ${pid + 7}")

            Thread.ofVirtual().start { Thread.sleep(300); Files.writeString(info, real) }
            assertNotNull(DaemonClient(config).ensureDaemon())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `a daemon without daemon json, or one that predates the token, still works`() {
        val config = config()
        val daemon = Daemon.start(config)
        try {
            Files.delete(config.home.resolve("daemon.json"))
            assertEquals(200, DaemonClient(config).send("GET", "/jobs").first)
        } finally {
            daemon.stop()
        }
    }
}
