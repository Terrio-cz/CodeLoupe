package codeloupe.brain

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.brain.BrainWorld.Companion.BILLING
import codeloupe.brain.BrainWorld.Companion.entry
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import codeloupe.secrets.PassphraseProtector
import codeloupe.secrets.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The brain routes and tools on a real daemon: a fixture repository, a workspace whose `brain.mjs` prints a canned export (node is a test prerequisite). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrainDaemonTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf(BILLING to BrainWorld.billing(), BrainWorld.CHECKOUT to BrainWorld.checkout()))
    private val workspace = TestRepos.tmpDir("brain-daemon").resolve("hub").also { it.resolve("brain/facts").createDirectories() }
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("brain-daemon-home")
    private val daemon: Daemon
    private val http = HttpClient.newHttpClient()
    private var writes = 0

    init {
        workspace.resolve("brain/brain.mjs").writeText(FAKE_BRAIN)
        daemon = Daemon.start(
            Config(home, port, 60_000, 120_000, 512, null, workspaces = WorkspacesConfig(repos = listOf(WorkspacesConfig.Repo(repo.toString())), brains = listOf(workspace.toString()))),
            secretStore = SecretStore(home.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000)),
        )
    }

    @AfterAll
    fun stop() = daemon.stop()

    private fun writeExport(entries: List<BrainEntry>) {
        val brain = workspace.resolve("brain")
        val paths = BrainPaths(facts = brain.resolve("facts").toString(), tasks = brain.resolve("tasks").toString(), feedback = brain.resolve("feedback.jsonl").toString(), config = brain.resolve("workspace.json").toString())
        val snapshot = BrainSnapshot(1, BrainWorkspaceInfo("hub", workspace.toString(), repo.toString().replace('\\', '/'), null, listOf("planner"), mapOf("planner" to BrainRoleBrief(listOf("review-patterns"), true, 40))), paths, entries)
        brain.resolve("export.json").writeText(JsonFormat.json.encodeToString(BrainSnapshot.serializer(), snapshot))
        // The index is rebuilt when a watched file changes: the export alone is not one.
        brain.resolve("facts/touch.md").writeText("x".repeat(++writes))
    }

    private fun post(path: String, body: JsonObject, token: Boolean = true): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        if (token) request.header(CodeLoupe.TOKEN_HEADER, daemon.token)
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun tool(name: String, args: JsonObject): String {
        val r = post("/api/$name", args)
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun fileEntry() = writeExport(listOf(entry("f-file0001", "total adds tax before rounding", task = "TER-5", files = listOf("billing/.../Billing.kt:3"))))

    private fun workspaceArgs() = buildJsonObject { put("workspace", workspace.toString()) }

    @Test
    fun `rank answers over HTTP with the code context and refuses a stranger, an unknown workspace and a bad body`() {
        fileEntry()
        val body = buildJsonObject {
            put("workspace", workspace.toString())
            put("files", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(BILLING)) })
            put("terms", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("total")) })
        }
        val ok = post("/brain/rank", body)
        assertEquals(200, ok.statusCode(), ok.body())
        val answer = Json.parseToJsonElement(ok.body()).jsonObject
        assertEquals("f-file0001", answer["sections"]!!.jsonObject["facts"]!!.jsonObject["hits"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("billing", answer["code"]!!.jsonObject["files"]!!.jsonArray.single().jsonObject["module"]!!.jsonPrimitive.content)
        assertEquals(401, post("/brain/rank", body, token = false).statusCode())
        assertEquals(404, post("/brain/rank", buildJsonObject { put("workspace", "C:/nowhere") }).statusCode())
        assertEquals(400, post("/brain/rank", buildJsonObject { put("terms", "not a list") }).statusCode())
    }

    @Test
    fun `an entry written after the index was built is in the very next answer`() {
        val rank = buildJsonObject { put("workspace", workspace.toString()); put("terms", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("sentinel")) }) }
        fun hits() = Json.parseToJsonElement(post("/brain/rank", rank).body()).jsonObject["sections"]!!.jsonObject["facts"]!!.jsonObject["hits"]!!.jsonArray.size
        assertEquals(0, hits())
        workspace.resolve("brain/facts/review-patterns.md").also { it.parent.createDirectories() }.writeText("+ sentinel\n")
        writeExport(listOf(entry("f-sentinel1", "a sentinel word appended to a facts file")))
        assertEquals(1, hits())
    }

    @Test
    fun `brain_brief starts the workspace engine with the arguments as separate words and the daemon's own address`() {
        val text = tool(
            "brain_brief",
            buildJsonObject {
                put("workspace", workspace.toString())
                put("role", "planner")
                put("task", "TER-9")
                put("terms", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("--who")); add(kotlinx.serialization.json.JsonPrimitive("rounding; rm -rf")) })
                put("files", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(BILLING)) })
            },
        )
        val echoed = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("brief", "planner", "TER-9", "who", "rounding; rm -rf", "--files", BILLING, "--json"), echoed["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(port.toString(), echoed["port"]!!.jsonPrimitive.content, "the engine finds this daemon again")
        assertContains(tool("brain_brief", buildJsonObject { put("workspace", workspace.toString()) }), "pass role")
    }

    @Test
    fun `brain_search answers from the index and hygiene over HTTP`() {
        fileEntry()
        val search = Json.parseToJsonElement(tool("brain_search", buildJsonObject { put("terms", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("rounding")) }) })).jsonObject
        assertEquals("f-file0001", search["hits"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse("anchors" in search["hits"]!!.jsonArray.first().jsonObject, "empty lists are left out of what an agent reads")
        val hygiene = Json.parseToJsonElement(post("/brain/hygiene", workspaceArgs()).body()).jsonObject
        assertTrue(hygiene["duplicates"]!!.jsonArray.isEmpty() && hygiene["dangling"]!!.jsonArray.isEmpty(), hygiene.toString())
    }

    @Test
    fun `symbol and context add the brain line for an anchored declaration once the index is built`() {
        writeExport(listOf(entry("f-symb0001", "total adds the tax twice when rate is 21", task = "TER-5", kind = "finding", anchors = listOf("@sym:com.example.billing.Billing.total"))))
        post("/brain/rank", buildJsonObject { put("workspace", workspace.toString()); put("terms", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("total")) }) })
        val symbol = tool("symbol", buildJsonObject { put("root", repo.toString()); put("name", "Billing.total") })
        assertContains(symbol, "fun total(amount: Int)")
        val note = symbol.lines().last()
        assertTrue(note.startsWith("brain: 1 on Billing.total"), note)
        assertContains(note, "f-symb0001 TER-5 finding: total adds the tax twice when rate is 21")
        assertFalse("brain:" in tool("symbol", buildJsonObject { put("root", repo.toString()); put("name", "Billing.tax(Int, Int)") }), "no entry is anchored on tax")
        assertTrue(tool("context", buildJsonObject { put("root", repo.toString()); put("name", "Billing.total") }).lines().last().startsWith("brain: 1 on Billing.total"))
    }

    private companion object {
        // Prints the canned export and, for brief, echoes how it was started.
        val FAKE_BRAIN = """
            const a = process.argv.slice(2);
            import fs from 'node:fs';
            if (a[0] === 'export') process.stdout.write(fs.readFileSync('brain/export.json', 'utf8'));
            else if (a[0] === 'brief') console.log(JSON.stringify({ args: a, port: process.env.CODELOUPE_PORT, home: process.env.CODELOUPE_HOME }));
            else process.exit(2);
        """.trimIndent()
    }
}
