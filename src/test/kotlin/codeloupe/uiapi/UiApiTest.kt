package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.MetricsConfig
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import codeloupe.secrets.PassphraseProtector
import codeloupe.secrets.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The read-only UI API on a daemon with a fixture repository and one worktree that changed a function. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UiApiTest {
    private val repo = TestRepos.fixtureRepo(
        "kotlin/sample",
        mapOf(
            "src/main/kotlin/demo/Billing.kt" to "package demo\n\nclass Billing {\n    fun total(a: Int): Int = a\n}\n",
            "src/main/kotlin/demo/Use.kt" to "package demo\n\nclass Use {\n    fun run() = Billing().total(2)\n}\n",
            "src/test/kotlin/demo/BillingTest.kt" to "package demo\n\nclass BillingTest {\n    fun checks() = Billing().total(1)\n}\n",
        ),
    )
    private val worktree = repo.resolveSibling(repo.fileName.toString().lowercase() + "-worktrees").createDirectories().resolve("CL-1")
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("ui-home")
    private val daemon = Daemon.start(
        Config(home, port, 60_000, 120_000, 512, null, workspaces = WorkspacesConfig(repos = listOf(WorkspacesConfig.Repo(repo.toString()))),
            metrics = MetricsConfig(transcriptDirs = listOf(TestRepos.tmpDir("ui-transcripts").toString())),
        ),
        secretStore = SecretStore(home.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000)),
    )
    private val http = HttpClient.newHttpClient()

    init {
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-1", worktree.toString(), "main")
        worktree.resolve("src/main/kotlin/demo/Billing.kt").writeText("package demo\n\nclass Billing {\n    fun total(a: Int): Int = a + 1\n    fun extra(): Int = 5\n}\n")
        TestRepos.git(worktree, "add", "-A")
        TestRepos.git(worktree, "commit", "-q", "-m", "CL-1 change total")
    }

    @AfterAll
    fun stop() = daemon.stop()

    private fun get(path: String, header: Boolean = true, method: String = "GET"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (header) builder.header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token)
        builder.method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString("{}"))
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(path: String): JsonObject {
        val r = get(path)
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun worktrees(query: String = ""): List<JsonObject> = (json("/ui-api/v1/worktrees$query")["items"] as JsonArray).map { it.jsonObject }

    private fun tool(name: String, vararg args: Pair<String, String>) {
        val body = buildJsonObject { args.forEach { (k, v) -> put(k, v) } }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$name")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val reply = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        assertTrue(reply.contains("\"ok\":true"), reply)
    }

    @Test
    @Order(1)
    fun `every screen answers 200 with no-store, also before the first query`() {
        for (resource in listOf("nav", "overview", "worktrees", "tasks", "index", "gaps", "environment", "settings", "events")) {
            val r = get("/ui-api/v1/$resource")
            assertEquals(200, r.statusCode(), "$resource: ${r.body()}")
            assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""), resource)
        }
    }

    @Test
    @Order(2)
    fun `queries make the repository, its worktrees, the telemetry and the builds show up`() {
        tool("find", "q" to "total", "root" to repo.toString())
        tool("find", "q" to "total", "root" to worktree.toString())

        val nav = json("/ui-api/v1/nav")
        assertEquals("1", nav["activeWorktrees"].toString())
        assertEquals("\"ready\"", nav["indexState"].toString())

        val items = worktrees()
        assertEquals(2, items.size)
        val main = items.single { it["isMain"].toString() == "true" }
        val feature = items.single { it["branch"].toString() == "\"CL-1\"" }
        assertEquals("\"CL-1\"", feature["taskId"].toString())
        assertEquals("1", feature["ahead"].toString())
        assertTrue(feature["changedFiles"].toString().toInt() >= 1)
        assertTrue(feature["queries24h"].toString().toInt() >= 1)
        assertEquals("\"fresh\"", feature["layer"].toString())
        assertTrue(main["id"].toString() != feature["id"].toString())
        assertEquals(12, feature["id"].toString().trim('"').length)

        assertTrue(worktrees("?layer=fresh").all { it["layer"].toString() == "\"fresh\"" })
        assertEquals(listOf(feature["id"]), worktrees("?q=cl-1").map { it["id"] })
        assertEquals(400, get("/ui-api/v1/worktrees?layer=nonsense").statusCode())
    }

    @Test
    @Order(3)
    fun `a worktree in detail lists its changed declarations with callers and tests`() {
        val id = worktrees().single { it["branch"].toString() == "\"CL-1\"" }["id"].toString().trim('"')
        val detail = json("/ui-api/v1/worktrees/$id")
        val text = detail.toString()
        assertTrue(text.contains("\"fqn\":\"demo.Billing.total\""), text)
        assertTrue(Regex("\"change\":\"body\"[^}]*\"fqn\":\"demo.Billing.total\"").containsMatchIn(text), text)
        assertTrue(Regex("\"change\":\"added\"[^}]*\"fqn\":\"demo.Billing.extra\"").containsMatchIn(text), text)
        assertTrue(text.contains("\"fqn\":\"demo.Use.run\"") && text.contains("\"calls\":\"demo.Billing.total\""), text)
        assertTrue(text.contains("BillingTest.kt") && text.contains("\"reason\":\"calls_changed\""), text)
        assertEquals(404, get("/ui-api/v1/worktrees/ffffffffffff").statusCode())
    }

    @Test
    @Order(4)
    fun `index health reports the repository, its counts and the builds from the event log`() {
        val index = json("/ui-api/v1/index")
        val text = index.toString()
        assertTrue(text.contains("\"state\":\"ready\""), text)
        val r = (index["repos"] as JsonArray).single().jsonObject
        assertTrue(r["files"].toString().toLong() > 0 && r["decls"].toString().toLong() > 0 && r["dbBytes"].toString().toLong() > 0, text)
        assertTrue(text.contains("\"status\":\"ok\""), text)
        assertTrue(text.contains("\"buildPeakRssMb\":600"), text)
    }

    @Test
    @Order(5)
    fun `the overview counts the calls the telemetry recorded`() {
        val o = json("/ui-api/v1/overview?range=24h")
        val text = o.toString()
        assertTrue(Regex("\"tool\":\"find\",\"calls\":2").containsMatchIn(text), text)
        assertTrue(Regex("\"queriedWorktrees\":2").containsMatchIn(text), text)
        assertEquals(400, get("/ui-api/v1/overview?range=1y").statusCode())
    }

    @Test
    @Order(6)
    fun `events start at the end of the log and report builds from a sequence number on`() {
        val start = json("/ui-api/v1/events")
        assertTrue(start["items"].toString() == "[]", start.toString())
        val last = start["lastSeq"].toString().toLong()
        assertTrue(last > 0)
        val all = json("/ui-api/v1/events?since=0").toString()
        assertTrue(all.contains("\"kind\":\"build_finished\""), all)
        assertEquals(start["epoch"], json("/ui-api/v1/events")["epoch"])
        assertEquals(400, get("/ui-api/v1/events?since=abc").statusCode())
    }

    @Test
    @Order(7)
    fun `settings, environment and gaps hold no secrets and no data the daemon lacks`() {
        val settings = json("/ui-api/v1/settings").toString()
        assertTrue(settings.contains("\"port\":$port"), settings)
        assertTrue(settings.contains(repo.fileName.toString()), settings)
        // The limits /status judges the daemon by, for the budget lines of the Overview charts.
        assertTrue(settings.contains("\"p95Ms\":1000") && settings.contains("\"queueWaitMs\":30000") && settings.contains("\"busyRate\":0.1"), settings)
        assertEquals("{\"keys\":[],\"storeReady\":true,\"rotationDays\":90}", json("/ui-api/v1/environment").toString())
        assertEquals("{\"events\":[]}", json("/ui-api/v1/environment/audit").toString())
        assertEquals("{\"summary\":[],\"items\":[],\"report\":null}", json("/ui-api/v1/gaps").toString())
        assertEquals(404, get("/ui-api/v1/tasks/CL-1").statusCode())
    }

    @Test
    @Order(8)
    fun `only GET is served, with the same guard as the rest of the daemon`() {
        val post = get("/ui-api/v1/nav", method = "POST")
        assertEquals(405, post.statusCode())
        assertEquals("GET", post.headers().firstValue("Allow").orElse(""))
        assertEquals("""{"error":{"code":"bad_request","message":"the UI API is read-only: GET only"}}""", post.body())
        assertEquals(403, get("/ui-api/v1/nav", header = false).statusCode())
        assertEquals(404, get("/ui-api/v1/nothing").statusCode())
        assertFalse(get("/ui-api/v1/nav").headers().firstValue("Access-Control-Allow-Origin").isPresent)
    }
}
