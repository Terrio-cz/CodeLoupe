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
import java.net.Socket
import java.net.URI
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
    private val port = 49152 + (Math.random() * 10000).toInt()
    private val config = Config(TestRepos.tmpDir("home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null)
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private var daemon = Daemon.start(config)
    private val http = JdkHttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun api(tool: String, args: JsonObject, headers: Map<String, String> = mapOf(CodeLoupe.HEADER to "1")): HttpResponse<String> {
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
        assertEquals(1, daemon.status().queue.done, "one build")
        assertEquals(1, daemon.status().repos.size)
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
    @Order(4)
    fun `responses close the connection`() {
        val response = rawGet("/status", "127.0.0.1:$port")
        assertTrue(response.startsWith("HTTP/1.1 200"), response)
        assertTrue(response.lowercase().contains("connection: close"), response)
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
        }
        client.connect(transport)
        assertEquals(listOf("find", "outline", "symbol"), client.listTools().tools.map { it.name }.sorted())
        val first = client.callTool("symbol", mapOf("root" to repo.toString(), "name" to "total"))
        assertContains((first.content.single() as TextContent).text, "fun total")
        daemon.stop()
        daemon = Daemon.start(config)
        val second = client.callTool("symbol", mapOf("root" to repo.toString(), "name" to "Registry.register"))
        assertContains((second.content.single() as TextContent).text, "fun register")
        assertEquals(0, daemon.status().queue.done, "restart reused the saved base index")
        client.close()
    }

    /** One request on a fresh socket, read until the server closes it. */
    private fun rawGet(path: String, host: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
        socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
    }
}
