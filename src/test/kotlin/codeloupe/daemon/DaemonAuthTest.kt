package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.ApiConfig
import codeloupe.config.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who may call the daemon: the token on every route that acts for the user, the open `/status`, the read tier of a daemon that is not strict (the default is strict). */
class DaemonAuthTest {
    private val http = HttpClient.newHttpClient()
    private val repo = TestRepos.fixtureRepo("kotlin/sample")

    private fun config(home: java.nio.file.Path = TestRepos.tmpDir("home"), strict: Boolean = true) =
        Config(home, ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null, api = ApiConfig(strict = strict))

    private fun send(port: Int, method: String, path: String, body: String? = null, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("content-type", "application/json")
        headers.forEach { (k, v) -> request.header(k, v) }
        request.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private val local = CodeLoupe.HEADER to "1"
    private fun withToken(token: String) = arrayOf(local, CodeLoupe.TOKEN_HEADER to token)
    private fun find() = """{"root":"${repo.toString().replace("\\", "/")}","q":"OrderService"}"""

    @Test
    fun `routes that act for the user refuse a caller without the token or with a wrong one`() {
        val config = config()
        val daemon = Daemon.start(config)
        try {
            val port = config.port
            val routes = listOf("GET" to "/jobs", "GET" to "/workspaces", "GET" to "/resources", "GET" to "/processes", "GET" to "/events", "GET" to "/ports",
                "GET" to "/ui-api/v1/nav", "GET" to "/status/history", "GET" to "/session-weight?path=x.jsonl", "POST" to "/jobs", "POST" to "/shutdown",
                "POST" to "/workspaces/release", "POST" to "/reconcile/run", "POST" to "/ports/free", "POST" to "/webhooks", "POST" to "/jobs/x/cancel")
            for ((method, path) in routes) {
                val none = send(port, method, path, if (method == "POST") "{}" else null, local)
                assertEquals(401, none.statusCode(), "no token: $method $path")
                assertContains(none.body(), "daemon token")
                assertEquals(401, send(port, method, path, if (method == "POST") "{}" else null, *withToken("x".repeat(64))).statusCode(), "wrong token: $method $path")
            }
            assertEquals(200, send(port, "GET", "/jobs", null, *withToken(daemon.token)).statusCode())
            assertEquals(200, send(port, "GET", "/ui-api/v1/nav", null, *withToken(daemon.token)).statusCode())
            assertEquals(0, daemon.status().jobs.running, "nothing ran")
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `status is open, proves the token to a client that asks without sending it, and never shows it`() {
        val config = config()
        val daemon = Daemon.start(config)
        try {
            val token = daemon.token
            val open = send(config.port, "GET", "/status", null, local)
            assertEquals(200, open.statusCode())
            assertNull(open.headers().firstValue(CodeLoupe.PROOF_HEADER).orElse(null), "no nonce, no proof")
            val nonce = "abcdef0123456789abcdef0123456789"
            val proven = send(config.port, "GET", "/status", null, local, CodeLoupe.NONCE_HEADER to nonce)
            assertEquals(DaemonToken.proof(token, nonce), proven.headers().firstValue(CodeLoupe.PROOF_HEADER).orElse(null))
            assertNotEquals(DaemonToken.proof(token, nonce), DaemonToken.proof(token, "another0123456789another0123456789"))
            assertNull(send(config.port, "GET", "/status", null, local, CodeLoupe.NONCE_HEADER to "bad nonce!").headers().firstValue(CodeLoupe.PROOF_HEADER).orElse(null))
            assertFalse(proven.body().contains(token), "the token is not in /status")
            assertFalse(Files.readString(config.home.resolve("daemon.log")).contains(token), "nor in the log")
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `a daemon that is not strict takes read-only code queries without the token and counts them, a mutating tool is refused`() {
        val config = config(strict = false)
        val daemon = Daemon.start(config)
        try {
            val port = config.port
            val found = send(port, "POST", "/api/find", find(), local)
            assertEquals(200, found.statusCode())
            assertContains(found.body(), "OrderService")
            assertEquals(1, daemon.status().auth.withoutToken)
            assertEquals(200, send(port, "POST", "/api/find", find(), *withToken(daemon.token)).statusCode())
            assertEquals(1, daemon.status().auth.withoutToken, "a call with the token is not counted")
            assertEquals(401, send(port, "POST", "/api/find", find(), *withToken("y".repeat(64))).statusCode(), "a stale token is refused, not ignored")

            for (tool in listOf("run", "env", "edit")) {
                val refused = send(port, "POST", "/api/$tool", "{}", local)
                assertEquals(401, refused.statusCode(), tool)
                assertContains(refused.body(), "daemon token")
            }
            assertNotEquals(401, send(port, "POST", "/api/run", """{"command":["git","--version"],"root":"${repo.toString().replace("\\", "/")}"}""", *withToken(daemon.token)).statusCode())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `over MCP a mutating tool says how to get the token, a read-only one answers on a daemon that is not strict`() {
        val config = config(strict = false)
        val daemon = Daemon.start(config)
        try {
            fun call(name: String, arguments: String, vararg headers: Pair<String, String>) = send(
                config.port, "POST", "/mcp",
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""",
                *headers, "accept" to "application/json, text/event-stream",
            ).body()

            val job = """{"action":"start","command":["git","--version"],"cwd":"${repo.toString().replace("\\", "/")}"}"""
            val without = call("job", job, local)
            assertContains(without, "daemon token")
            assertEquals(0, daemon.status().jobs.running + daemon.status().jobs.queued, "nothing was started")
            assertContains(call("run", """{"command":["git","--version"]}""", local), "daemon token")
            assertContains(call("symbol", """{"root":"${repo.toString().replace("\\", "/")}","name":"total"}""", local), "fun total")
            assertFalse(call("job", """{"action":"status"}""", *withToken(daemon.token)).contains("daemon token"))
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `by default the daemon wants the token for the code queries too`() {
        val config = config()
        val daemon = Daemon.start(config)
        try {
            assertEquals(401, send(config.port, "POST", "/api/find", find(), local).statusCode())
            assertEquals(401, send(config.port, "POST", "/hook", "{}", local).statusCode())
            assertEquals(200, send(config.port, "POST", "/api/find", find(), *withToken(daemon.token)).statusCode())
            assertTrue(daemon.status().auth.strict)
            assertEquals(200, send(config.port, "GET", "/status", null, local).statusCode())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `the token is made owner-only, survives a restart, and a replaced file takes effect without one`() {
        val config = config()
        val first = Daemon.start(config)
        val file = config.home.resolve(DaemonToken.FILE)
        try {
            val token = first.token
            assertEquals(token, DaemonToken.read(config.home))
            assertTrue(Files.readString(file).startsWith("${CodeLoupe.TOKEN_HEADER}: "), "the file is the header line a client sends")
            assertTrue(DaemonToken.valid(token))
            if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config.home)))
            }
            first.stop()
            val again = Daemon.start(config)
            try {
                assertEquals(token, again.token, "running clients stay valid across a restart")
                val rotated = "r".repeat(64)
                Thread.sleep(20)
                Files.writeString(file, rotated)
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
                assertEquals(401, send(config.port, "GET", "/jobs", null, *withToken(token)).statusCode(), "the old token no longer works")
                assertEquals(200, send(config.port, "GET", "/jobs", null, *withToken(rotated)).statusCode())
                Files.delete(file)
                assertEquals(200, send(config.port, "GET", "/jobs", null, *withToken(rotated)).statusCode(), "a deleted file is written again with the token in force")
                assertEquals(rotated, DaemonToken.read(config.home))
            } finally {
                again.stop()
            }
        } finally {
            runCatching { first.stop() }
        }
    }

    @Test
    fun `a garbled or short token file is replaced, never accepted as a token`() {
        val home = TestRepos.tmpDir("home")
        Files.writeString(home.resolve(DaemonToken.FILE), "short")
        val config = config(home)
        val daemon = Daemon.start(config)
        try {
            assertTrue(DaemonToken.valid(daemon.token))
            assertEquals(401, send(config.port, "GET", "/jobs", null, *withToken("short")).statusCode())
            assertEquals(401, send(config.port, "GET", "/jobs", null, local, CodeLoupe.TOKEN_HEADER to "").statusCode(), "an empty token is a wrong token")
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `the status JSON names the auth state`() {
        val config = config()
        val daemon = Daemon.start(config)
        try {
            val auth = Json.parseToJsonElement(send(config.port, "GET", "/status", null, local).body()).jsonObject["auth"]!!.jsonObject
            assertEquals("true", auth["strict"]!!.jsonPrimitive.content)
        } finally {
            daemon.stop()
        }
    }
}
