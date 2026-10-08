package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.BudgetsConfig
import codeloupe.config.Config
import codeloupe.config.WriteConfig
import codeloupe.tools.FindTool
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * A client caches the tool list as part of the prompt prefix: the list a daemon serves must be the same bytes whatever the
 * daemon has been asked, which repositories it knows and how often it restarted, apart from the decision taken at its start.
 */
class ToolListStabilityTest {
    private val http = JdkHttpClient.newHttpClient()

    private fun config(write: WriteConfig = WriteConfig(mode = WriteConfig.OFF), budgets: BudgetsConfig = BudgetsConfig()): Config {
        val port = ServerSocket(0).use { it.localPort }
        return Config(TestRepos.tmpDir("toollist"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, write = write, budgets = budgets)
    }

    private fun listing(config: Config): String = runBlocking {
        val client = Client(Implementation(name = "test", version = "0"))
        client.connect(StreamableHttpClientTransport(HttpClient(ClientCIO) { install(SSE) }, "http://127.0.0.1:${config.port}/mcp") { headers.append(CodeLoupe.HEADER, "1") })
        try {
            Json.encodeToString(ListToolsResult.serializer(), client.listTools())
        } finally {
            client.close()
        }
    }

    private fun toolList(config: Config) = Json.parseToJsonElement(
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${config.port}/status")).GET().build(), HttpResponse.BodyHandlers.ofString()).body(),
    ).jsonObject["toolList"]!!.jsonObject

    private fun fingerprint(config: Config) = toolList(config)["fingerprint"]!!.jsonPrimitive.content

    private fun call(config: Config, tool: String, vararg args: Pair<String, String>) {
        val body = args.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\\", "/")}\"" }
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${config.port}/api/$tool")).header(CodeLoupe.HEADER, "1").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    @Test
    fun `the list is the same bytes before and after the daemon works, and on a daemon with other repositories and settings`() {
        val a = config()
        val daemonA = Daemon.start(a)
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        try {
            val before = listing(a)
            val fingerprint = fingerprint(a)
            call(a, "find", "root" to repo.toString(), "q" to "OrderService")
            call(a, "outline", "root" to repo.toString())
            assertEquals(before, listing(a), "the list does not move with the work")
            assertEquals(fingerprint, fingerprint(a))
            assertEquals(1, daemonA.status().repos.size)

            val b = config(budgets = BudgetsConfig(rssMb = 77))
            val daemonB = Daemon.start(b)
            try {
                assertEquals(before, listing(b), "another home, no repository, other budgets")
                assertEquals(fingerprint, fingerprint(b))
            } finally {
                daemonB.stop()
            }
        } finally {
            daemonA.stop()
        }
    }

    @Test
    fun `the fingerprint says what the list holds and survives restarts`() {
        val on = config(WriteConfig(mode = WriteConfig.ON))
        val off = config(WriteConfig(mode = WriteConfig.OFF))
        val auto = config(WriteConfig(mode = WriteConfig.AUTO))
        val seen = mutableListOf<String>()
        var withEdit = 0
        repeat(2) {
            val daemon = Daemon.start(on)
            try {
                seen += fingerprint(on)
                assertTrue(toolList(on)["editOffered"]!!.jsonPrimitive.content.toBoolean())
                withEdit = toolList(on)["tools"]!!.jsonPrimitive.content.toInt()
            } finally {
                daemon.stop()
            }
        }
        assertEquals(seen[0], seen[1], "two restarts, one fingerprint")
        val daemonOff = Daemon.start(off)
        val daemonAuto = Daemon.start(auto)
        try {
            assertNotEquals(seen[0], fingerprint(off), "edit changes the list")
            assertEquals(withEdit - 1, toolList(off)["tools"]!!.jsonPrimitive.content.toInt())
            assertEquals(fingerprint(off), fingerprint(auto), "auto with no verdict yet offers what off offers")
            assertFalse(toolList(auto)["editOffered"]!!.jsonPrimitive.content.toBoolean())
        } finally {
            daemonOff.stop()
            daemonAuto.stop()
        }
    }

    @Test
    fun `a gate that opens later does not change the list`() {
        var open = false
        val offered = OfferedTools(listOf(FindTool), FindTool, { open })
        open = true
        assertEquals(listOf(FindTool), offered.tools)
        assertFalse(offered.editOffered)
        val second = OfferedTools(listOf(FindTool), FindTool) { open }
        assertTrue(second.editOffered)
        assertEquals(2, second.tools.size)
    }
}
