package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.engine.cio.CIO as ClientCIO

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DaemonTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val config = Config(TestRepos.tmpDir("home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null)
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private var daemon = Daemon.start(config)
    private val http = JdkHttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun api(tool: String, args: JsonObject, headers: Map<String, String> = mapOf(CodeLoupe.HEADER to "1", CodeLoupe.TOKEN_HEADER to daemon.token)): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$tool")).header("content-type", "application/json")
        headers.forEach { (k, v) -> request.header(k, v) }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(args.toString())).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>) = Json.parseToJsonElement(response.body()).jsonObject

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    @Order(1)
    fun `concurrent first queries share one build`() {
        val a = Thread.ofVirtual().start { assertContains(json(api("find", args("root" to repo.toString(), "q" to "OrderService")))["text"]!!.jsonPrimitive.content, "class OrderService") }
        val b = json(api("outline", args("root" to repo.toString(), "target" to "Registry")))
        a.join()
        assertTrue(b["ok"]!!.jsonPrimitive.boolean, b.toString())
        assertEquals(1, daemon.status().queue.heavy.done, "one build")
        assertEquals(1, daemon.status().repos.size)
        val status = daemon.status()
        assertEquals(2, status.latency.window, "both calls are in the latency window")
        assertEquals(setOf("find", "outline"), status.latency.byTool.keys)
        // The test JVM holds the daemon and the whole suite, so its RSS says nothing; the other budgets must hold.
        assertTrue(status.budgets.warnings.none { !it.startsWith("rss ") }, status.budgets.warnings.toString())
        val history = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/status/history")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, history.statusCode())
        assertContains(history.body(), "\"rssMb\"")
    }

    @Test
    @Order(2)
    fun `worktrees of one repository share its index - unknown roots are errors`() {
        val sub = json(api("find", args("root" to repo.resolve("src/main").toString(), "q" to "Registry")))
        assertTrue(sub["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, daemon.status().repos.size)
        val bad = json(api("find", args("root" to TestRepos.tmpDir("nogit").toString(), "q" to "x")))
        assertFalse(bad["ok"]!!.jsonPrimitive.boolean)
        assertContains(bad["text"]!!.jsonPrimitive.content, "not inside a git repository")
    }

    @Test
    @Order(3)
    fun `rejects browser-shaped and header-less requests`() {
        assertEquals(403, api("find", args("root" to repo.toString(), "q" to "x"), mapOf(CodeLoupe.HEADER to "1", "origin" to "https://evil.example")).statusCode())
        assertEquals(403, api("find", args("root" to repo.toString(), "q" to "x"), emptyMap()).statusCode())
        // DNS rebinding: a page on evil.example resolving to 127.0.0.1 sends its own Host.
        assertTrue(rawGet("/status", "evil.example").startsWith("HTTP/1.1 403"))
    }

    @Test
    @Order(3)
    fun `a body declared far too large is refused before it is read`() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write("POST /jobs HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n${CodeLoupe.HEADER}: 1\r\nContent-Length: 9000000\r\nContent-Type: application/json\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val answer = socket.getInputStream().bufferedReader().readLine()
            assertTrue(answer.startsWith("HTTP/1.1 403"), answer)
        }
    }

    @Test
    @Order(4)
    fun `responses close the connection`() {
        val response = rawGet("/status", "127.0.0.1:$port")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue(response.lowercase().contains("connection: close"), response)
    }

    @Test
    @Order(4)
    fun `malformed API calls get a JSON error and a log line`() {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/find")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token)
            .POST(HttpRequest.BodyPublishers.ofString("{bad")).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(500, response.statusCode())
        assertTrue(json(response).containsKey("error"), response.body())
        assertContains(Files.readString(config.home.resolve("daemon.log")), "request POST /api/find failed")
        val relative = json(api("find", args("root" to ".", "q" to "x")))
        assertContains(relative["text"]!!.jsonPrimitive.content, "root must be an absolute path")
    }

    @Test
    @Order(5)
    fun `a second daemon on the same port refuses to start`() {
        assertFailsWith<BindException> { Daemon.start(config) }
    }

    @Test
    @Order(6)
    fun `MCP - tools listed and callable, client survives a daemon restart`() = runBlocking {
        val client = Client(Implementation(name = "test", version = "0"))
        val transport = StreamableHttpClientTransport(HttpClient(ClientCIO) { install(SSE) }, "http://127.0.0.1:$port/mcp") {
            headers.append(CodeLoupe.HEADER, "1")
            headers.append(CodeLoupe.TOKEN_HEADER, daemon.token)
        }
        client.connect(transport)
        assertEquals(listOf("calls", "changes", "context", "doc", "env", "find", "grep", "hierarchy", "job", "outline", "run", "symbol", "task_code", "usages"), client.listTools().tools.map { it.name }.sorted())
        val first = client.callTool("symbol", mapOf("root" to repo.toString(), "name" to "total"))
        assertContains((first.content.single() as TextContent).text, "fun total")
        daemon.stop()
        daemon = Daemon.start(config)
        val second = client.callTool("symbol", mapOf("root" to repo.toString(), "name" to "Registry.register"))
        assertContains((second.content.single() as TextContent).text, "fun register")
        assertEquals(0, daemon.status().queue.heavy.done, "restart reused the saved base index")
        client.close()
    }

    /** One request on a fresh socket, read until the server closes it. */
    private fun rawGet(path: String, host: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
        socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
    }
}
