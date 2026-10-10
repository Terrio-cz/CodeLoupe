package codeloupe.tools

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.ToolsConfig
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.net.http.HttpClient as JdkHttpClient

/** `batch` in a daemon that offers it: order, labels, per-part errors, refusals, the size cap and the catalog cost. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchToolTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val config = Config(TestRepos.tmpDir("batch-home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, tools = ToolsConfig(batch = true))
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private val daemon = Daemon.start(config)
    private val http = JdkHttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun call(tool: String, args: JsonObject): String {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$tool")).POST(HttpRequest.BodyPublishers.ofString(args.toString()))
            .header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).header("content-type", "application/json").build()
        return JsonFormat.json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject.getValue("text").jsonPrimitive.content
    }

    private fun spec(tool: String, vararg args: Pair<String, String>) = buildJsonObject {
        put("tool", tool)
        put("args", buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
    }

    private fun batch(vararg calls: JsonObject, root: String? = repo.toString()): String =
        call("batch", buildJsonObject { if (root != null) put("root", root); put("calls", JsonArray(calls.toList())) })

    private fun single(tool: String, vararg args: Pair<String, String>): String = call(tool, buildJsonObject { put("root", repo.toString()); args.forEach { (k, v) -> put(k, v) } })

    @Test
    fun `parts come in the order given, each labelled, and equal what the calls answer alone`() {
        val answer = batch(spec("find", "q" to "OrderService"), spec("outline", "target" to "OrderService"), spec("symbol", "name" to "OrderService"))
        val first = answer.indexOf("[1] find OrderService")
        val second = answer.indexOf("[2] outline OrderService")
        val third = answer.indexOf("[3] symbol OrderService")
        assertTrue(first in 0 until second && second < third, answer)
        assertContains(answer, single("find", "q" to "OrderService").trim())
        assertContains(answer, single("symbol", "name" to "OrderService").trim())
    }

    @Test
    fun `a failing part reports its error and the others still answer`() {
        val answer = batch(spec("symbol", "name" to "OrderService"), spec("symbol"), spec("find", "q" to "OrderService", "limit" to "x"), spec("find", "q" to "OrderService"))
        assertContains(answer, "[2] symbol (failed)")
        assertContains(answer, "Required at name")
        assertContains(answer, "[3] find OrderService (failed)")
        assertContains(answer, "[4] find OrderService\n")
        assertContains(answer, "[1] symbol OrderService\n")
    }

    @Test
    fun `write tools, process tools, unknown tools and batch itself are refused inside a batch`() {
        val answer = batch(spec("edit", "op" to "delete", "name" to "OrderService"), spec("run", "command" to "x"), spec("nonesuch"), spec("batch"), spec("find", "q" to "OrderService"))
        for (n in 1..4) assertContains(answer, "[$n] ")
        assertContains(answer, "\"edit\" is not a read-only tool")
        assertContains(answer, "\"run\" is not a read-only tool")
        assertContains(answer, "\"nonesuch\" is not a read-only tool")
        assertContains(answer, "\"batch\" is not a read-only tool")
        assertContains(answer, "[5] find OrderService\n")
        assertFalse(answer.contains("[5] find OrderService (failed)"))
    }

    @Test
    fun `a call may carry its own root and may be flattened without args`() {
        val flat = buildJsonObject { put("tool", "find"); put("q", "OrderService"); put("root", repo.toString()) }
        val answer = batch(flat, buildJsonObject { put("tool", "find"); put("args", buildJsonObject { put("q", "OrderService"); put("root", repo.toString()) }) }, root = null)
        assertContains(answer, "[2] find OrderService\n")
        assertContains(answer, "class OrderService")
    }

    @Test
    fun `an empty batch and one over the limit are refused whole`() {
        assertContains(batch(), "pass calls")
        val many = Array(BatchTool.MAX_CALLS + 1) { spec("find", "q" to "OrderService") }
        assertContains(batch(*many), "at most ${BatchTool.MAX_CALLS} calls")
    }

    @Test
    fun `a part over its share is cut with a note and the rest still follows`() {
        val big = TestRepos.fixtureRepo("kotlin/sample", mapOf("src/main/kotlin/demo/Big.kt" to TestRepos.bigClass("demo", "BigThing", 900)))
        val answer = batch(buildJsonObject { put("tool", "symbol"); put("args", buildJsonObject { put("name", "BigThing"); put("full", true) }) }, spec("find", "q" to "OrderService"), root = big.toString())
        assertContains(answer, "more chars; call it alone for the rest")
        assertContains(answer, "[2] find OrderService")
    }

    @Test
    fun `the tool costs little in the list and is read-only`() {
        val tool = BatchTool({ emptyList() }) { _, _ -> BatchTool.Outcome(true, "") }
        val chars = tool.description.length + Tools.properties(tool).toString().length
        assertTrue(chars < 900, "batch adds $chars chars (about ${chars / 5} tokens at 5 chars per token) to the tool list")
        assertFalse(tool.mutating)
        assertNotNull(Tools.named("find"))
        assertTrue(BatchTool.READ_ONLY.all { name -> Tools.named(name)?.mutating != true })
    }

    @Test
    fun `config parses tools batch and defaults it off`() {
        assertFalse(ToolsConfig.parse(JsonObject(emptyMap())).batch)
        assertTrue(ToolsConfig.parse(JsonObject(mapOf("tools" to JsonObject(mapOf("batch" to JsonPrimitive(true)))))).batch)
        assertFalse(ToolsConfig.parse(JsonObject(mapOf("tools" to JsonObject(mapOf("batch" to JsonPrimitive("yes")))))).batch)
        assertEquals(false, Config(config.home, 1, 1, 1, 1, null).tools.batch)
    }
}
