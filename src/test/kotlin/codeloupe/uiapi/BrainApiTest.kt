package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.brain.BrainFacts
import codeloupe.brain.BrainIndexParser
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import codeloupe.secrets.PassphraseProtector
import codeloupe.secrets.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Brain endpoints on a daemon whose workspace has a fake `brain/brain.mjs` (node is a test prerequisite, as for the hooks). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrainApiTest {
    private val workspace = TestRepos.tmpDir("brain-ws").resolve("hub").also { it.createDirectories() }
    private val broken = TestRepos.tmpDir("brain-broken").resolve("broken").also { it.createDirectories() }
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("brain-home")
    private val daemon: Daemon
    private val http = HttpClient.newHttpClient()

    init {
        workspace.resolve("brain/facts/review-patterns").createDirectories()
        workspace.resolve("brain/brain.mjs").writeText(FAKE_BRAIN)
        workspace.resolve("brain/facts/db.md").writeText("# db\nDurable facts.\n\n+ shared DB is postgres <!-- 2026-09-23 -->\n! never run DDL there\n")
        workspace.resolve("brain/facts/review-patterns/sql.md").writeText("+ use anti-join\n")
        workspace.resolve("brain/facts/old.md").writeText("+ read from the file <!-- 2026-09-01 -->\n")
        broken.resolve("brain").createDirectories()
        broken.resolve("brain/brain.mjs").writeText("console.error('boom: index is corrupt'); process.exit(3);\n")
        daemon = Daemon.start(
            Config(home, port, 60_000, 120_000, 512, null, workspaces = WorkspacesConfig(brains = listOf(workspace.toString(), broken.toString(), workspace.resolveSibling("missing").toString()))),
            secretStore = SecretStore(home.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000)),
        )
    }

    @AfterAll
    fun stop() = daemon.stop()

    private fun get(path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun json(path: String): JsonObject {
        val r = get(path)
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun workspaces(): List<JsonObject> = json("/ui-api/v1/brain")["workspaces"]!!.jsonArray.map { it.jsonObject }

    private fun enc(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

    @Test
    fun `the list holds the registered workspaces with a brain, their topics, totals and a broken brain as an error`() {
        val all = workspaces()
        assertEquals(listOf("hub-brain", "broken"), all.map { it["name"]!!.jsonPrimitive.content })
        val hub = all.first()
        assertEquals(12, hub["id"]!!.jsonPrimitive.content.length)
        assertEquals(listOf("db", "review-patterns/sql"), hub["topics"]!!.jsonArray.map { it.jsonObject["topic"]!!.jsonPrimitive.content })
        assertEquals("3", hub["entries"]!!.jsonPrimitive.content)
        assertEquals("100", hub["bytes"]!!.jsonPrimitive.content)
        val bad = all.last()
        assertEquals("failed", bad["errorCode"]!!.jsonPrimitive.content)
        assertTrue(bad["error"]!!.jsonPrimitive.content.contains("boom"), bad.toString())
        assertEquals(0, bad["topics"]!!.jsonArray.size)
    }

    @Test
    fun `a topic is shown by id and name, with marks, dates and the engine metadata, and cannot leave the facts directory`() {
        val id = workspaces().first()["id"]!!.jsonPrimitive.content
        val entries = json("/ui-api/v1/brain/$id/topic?name=db")["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        assertEquals("shared DB is postgres", entries[0]["text"]!!.jsonPrimitive.content)
        assertEquals("+", entries[0]["mark"]!!.jsonPrimitive.content)
        assertEquals("2026-09-23", entries[0]["stamp"]!!.jsonPrimitive.content)
        assertEquals("f-aaaaaaaa", entries[0]["id"]!!.jsonPrimitive.content)
        assertEquals("TER-5", entries[0]["task"]!!.jsonPrimitive.content)
        assertEquals("!", entries[1]["mark"]!!.jsonPrimitive.content)
        assertEquals(1, json("/ui-api/v1/brain/$id/topic?name=${enc("review-patterns/sql")}")["entries"]!!.jsonArray.size)
        for (name in listOf("../brain", "..%2F..%2Fsecret", "db.md/..", "/etc/passwd", "C:%2Fx", "a%5Cb")) {
            assertEquals(400, get("/ui-api/v1/brain/$id/topic?name=$name").statusCode(), name)
        }
        assertEquals(404, get("/ui-api/v1/brain/$id/topic?name=nothing").statusCode())
        assertEquals(400, get("/ui-api/v1/brain/$id/topic").statusCode())
    }

    @Test
    fun `an engine that predates show is read from the facts file, without metadata`() {
        val id = workspaces().first()["id"]!!.jsonPrimitive.content
        val entries = json("/ui-api/v1/brain/$id/topic?name=old")["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals("read from the file", entries.single()["text"]!!.jsonPrimitive.content)
        assertEquals("2026-09-01", entries.single()["stamp"]!!.jsonPrimitive.content)
        assertTrue(entries.single()["id"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `an id that the registry does not know is not found, a path in its place too`() {
        assertEquals(404, get("/ui-api/v1/brain/ffffffffffff/topic?name=db").statusCode())
        assertEquals(404, get("/ui-api/v1/brain/${enc(workspace.toString())}/search?q=x").statusCode())
        assertEquals(404, get("/ui-api/v1/brain/${enc(workspace.toString().replace('\\', '/'))}/topic?name=db").statusCode())
    }

    @Test
    fun `search passes terms, topics and the task switch to the CLI as separate arguments`() {
        val id = workspaces().first()["id"]!!.jsonPrimitive.content
        val plain = json("/ui-api/v1/brain/$id/search?q=${enc("anti join; rm -rf")}")
        assertEquals(listOf("search", "anti", "join;", "rm", "rf", "--limit", "50", "--json"), args(plain))
        val scoped = json("/ui-api/v1/brain/$id/search?q=x&topic=db&topic=${enc("review-patterns/sql")}&tasks=1&limit=7")
        assertEquals(listOf("search", "x", "--in", "db", "--in", "review-patterns/sql", "--tasks", "--limit", "7", "--json"), args(scoped))
        assertEquals("TER-5", scoped["hits"]!!.jsonArray[1].jsonObject["task"]!!.jsonPrimitive.content)
        assertEquals("1", scoped["more"]!!.jsonPrimitive.content)
        assertEquals(400, get("/ui-api/v1/brain/$id/search?q=").statusCode())
        assertEquals(400, get("/ui-api/v1/brain/$id/search?q=x&topic=..%2Fx").statusCode())
        assertEquals(400, get("/ui-api/v1/brain/$id/search?q=x&limit=0").statusCode())
    }

    @Test
    fun `a search that fails in the CLI is unavailable with the reason`() {
        val id = workspaces().last()["id"]!!.jsonPrimitive.content
        val r = get("/ui-api/v1/brain/$id/search?q=x")
        assertEquals(503, r.statusCode())
        assertTrue(r.body().contains("unavailable") && r.body().contains("boom"), r.body())
    }

    @Test
    fun `the index parser reads the JSON and the older text listing, the facts parser the entry lines`() {
        val json = BrainIndexParser.parse("""{"workspace":"t","topics":[{"topic":"a/b","entries":2,"bytes":9,"about":"x"},{"topic":"../bad","entries":1}]}""")
        assertEquals("t", json.workspace)
        assertEquals(listOf("a/b"), json.topics.map { it.topic })
        val text = BrainIndexParser.parse("db      81  database facts\nreview-patterns/sql   26  sql\n")
        assertEquals(listOf(81, 26), text.topics.map { it.entries })
        assertEquals("database facts", text.topics.first().about)
        assertNull(BrainFacts.parse("- bare\n# h\n").firstOrNull { it.text == "h" }, "headers are skipped")
        assertFalse(BrainFacts.parse("+ a <!-- d -->").single().text.contains("<!--"))
    }

    private fun args(search: JsonObject): List<String> = (search["terms"] as JsonArray).map { it.jsonPrimitive.content }

    private companion object {
        // Answers `index --json` and echoes the search arguments back as its terms, so a test sees exactly what the CLI got.
        val FAKE_BRAIN = """
            const a = process.argv.slice(2);
            if (a[0] === 'index') {
              console.log(JSON.stringify({ workspace: 'hub-brain', topics: [
                { topic: 'db', entries: 2, bytes: 60, about: 'database facts' },
                { topic: 'review-patterns/sql', entries: 1, bytes: 40, about: 'sql' } ] }));
            } else if (a[0] === 'search') {
              console.log(JSON.stringify({ terms: a, hits: [
                { where: 'facts/db.md:3', topic: 'db', line: 3, text: 'shared DB', hits: 2 },
                { where: 'tasks/TER-5.md:40', task: 'TER-5', line: 40, text: 'finding', hits: 1 } ], more: 1 }));
            } else if (a[0] === 'show') {
              if (a[1] === 'old') process.exit(2);
              if (a[1] === 'nothing') { console.error('no facts topic nothing; node brain/brain.mjs index lists them'); process.exit(1); }
              const mk = (text, marker, id, task, at) => ({ where: 'facts/' + a[1] + '.md:1', topic: a[1], text, marker, id, kind: 'fact', task, at, anchors: [], superseded: false });
              console.log(JSON.stringify({ topic: a[1], entries: a[1] === 'db'
                ? [mk('+ shared DB is postgres', '+', 'f-aaaaaaaa', 'TER-5', '2026-09-23T10:00'), mk('! never run DDL there', '!', 'f-bbbbbbbb', null, '2026-09-24')]
                : [mk('+ use anti-join', '+', 'f-cccccccc', null, null)] }));
            } else { process.exit(2); }
        """.trimIndent()
    }
}
