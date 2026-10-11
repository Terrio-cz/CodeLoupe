package codeloupe.cli

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.DaemonToken
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** An answer without a body is a message, not a parser exception, and a daemon this client has just started gets one more try. */
class DaemonClientEmptyBodyTest {
    private val config = Config(TestRepos.tmpDir("empty-body-home"), ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null)

    /** A stand-in daemon: `/status` names our home; each `/api/find` answers with the next of [answers] (status to body, null body = none). */
    private inner class FakeDaemon(private val answers: List<Pair<Int, String?>>) {
        val posts = AtomicInteger()
        private var server: HttpServer? = null

        fun start() {
            val rogue = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 0)
            rogue.createContext("/status") { exchange ->
                val body = """{"name":"codeloupe","pid":${ProcessHandle.current().pid()},"home":"${config.home.toString().replace("\\", "\\\\")}"}""".toByteArray()
                exchange.respond(200, body)
            }
            rogue.createContext("/api/find") { exchange ->
                val (status, body) = answers[minOf(posts.getAndIncrement(), answers.lastIndex)]
                exchange.requestBody.readAllBytes()
                exchange.respond(status, body?.toByteArray())
            }
            rogue.start()
            server = rogue
        }

        fun stop() = server?.stop(0)

        private fun HttpExchange.respond(status: Int, body: ByteArray?) {
            sendResponseHeaders(status, if (body == null) -1 else body.size.toLong())
            if (body != null) responseBody.use { it.write(body) }
            close()
        }
    }

    private val outcome = """{"ok":true,"text":"found it"}"""

    /** The daemon is not up when the client looks, so the client starts it: [FakeDaemon.start] stands for the detached process. */
    private fun client(daemon: FakeDaemon, alreadyRunning: Boolean): DaemonClient {
        if (alreadyRunning) daemon.start()
        return DaemonClient(config, launcher = { daemon.start() })
    }

    @Test
    fun `an empty answer from a daemon the client has just started is asked for once more`() {
        val daemon = FakeDaemon(listOf(200 to null, 200 to outcome))
        try {
            val reply = client(daemon, alreadyRunning = false).call("find", JsonObject(emptyMap()))
            assertEquals("found it", reply.text)
            assertEquals(2, daemon.posts.get())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `an empty answer that stays empty is reported with the status and what to do, after one more try`() {
        val daemon = FakeDaemon(listOf(200 to null))
        try {
            val failure = assertFailsWith<IllegalStateException> { client(daemon, alreadyRunning = false).call("find", JsonObject(emptyMap())) }
            assertContains(failure.message.orEmpty(), "HTTP 200 with an empty body")
            assertContains(failure.message.orEmpty(), "daemon.log")
            assertEquals(2, daemon.posts.get())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `a refusal without a body names the token, and a daemon that was already running is not asked twice`() {
        val daemon = FakeDaemon(listOf(401 to null))
        try {
            val failure = assertFailsWith<IllegalStateException> { client(daemon, alreadyRunning = true).call("find", JsonObject(emptyMap())) }
            assertContains(failure.message.orEmpty(), "HTTP 401")
            assertContains(failure.message.orEmpty(), DaemonToken.FILE)
            assertEquals(1, daemon.posts.get())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `send reports an error status without a body as text, not as an empty string`() {
        val daemon = FakeDaemon(listOf(401 to null))
        try {
            val (status, json) = client(daemon, alreadyRunning = true).send("POST", "/api/find", JsonObject(emptyMap()))
            assertEquals(401, status)
            assertTrue(json["error"].toString().contains("refused the request"), json.toString())
            assertEquals(1, daemon.posts.get())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `a normal answer is taken at once`() {
        val daemon = FakeDaemon(listOf(200 to outcome))
        try {
            assertEquals("found it", client(daemon, alreadyRunning = false).call("find", JsonObject(emptyMap())).text)
            assertEquals(1, daemon.posts.get())
        } finally {
            daemon.stop()
        }
    }
}
