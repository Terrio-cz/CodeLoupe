package codeloupe.know

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.daemon.TestToken
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/know` through a real daemon: the token, the write path with dedupe, search, one fact, the stale list and verify. */
class KnowRoutesTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf(BILLING to "package demo\n\nclass Billing {\n    fun total(a: Int): Int = a\n}\n"))
    private val home = TestRepos.tmpDir("home")
    private val port = ServerSocket(0).use { it.localPort }
    private val daemon = Daemon.start(Config(home, port, 60_000, 120_000, 512, null, overlayCheckMs = 0))

    @AfterTest
    fun cleanUp() {
        daemon.stop()
        home.toFile().deleteRecursively()
        repo.toFile().deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null, token: String? = TestToken.of(port)): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1")
        token?.let { builder.header(CodeLoupe.TOKEN_HEADER, it) }
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>): JsonObject = JsonFormat.json.parseToJsonElement(response.body()).jsonObject

    private fun search(q: String) = json(call("GET", "/know/search?q=${URLEncoder.encode(q, Charsets.UTF_8)}"))["hits"].toString()

    @Test
    fun `every route needs the daemon token`() {
        assertEquals(401, call("GET", "/know/search?q=anything", token = null).statusCode())
        assertEquals(401, call("POST", "/know", """{"scope":"repo","text":"x"}""", token = null).statusCode())
        assertEquals(401, call("GET", "/know/stale", token = null).statusCode())
        assertEquals(401, call("GET", "/know/k1", token = null).statusCode())
        assertEquals(401, call("POST", "/know/k1/verify", token = null).statusCode())
    }

    @Test
    fun `facts are written once, found, read by id, and a bad request says what is wrong`() {
        val added = json(call("POST", "/know", """{"scope":"area:docker","kind":"environment","text":"the shared stack keeps its data in a named volume","task":"TER-3"}"""))
        assertEquals("added", added["action"]!!.jsonPrimitive.content)
        val id = added["fact"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val again = json(call("POST", "/know", """{"scope":"area:docker","text":"The shared stack keeps its data in a named volume."}"""))
        assertEquals("updated", again["action"]!!.jsonPrimitive.content)
        assertEquals(id, again["fact"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        assertContains(search("named volume data"), "$id environment area:docker")
        val fact = json(call("GET", "/know/$id"))
        assertEquals("active", fact["status"]!!.jsonPrimitive.content)
        assertEquals("TER-3", fact["source"]!!.jsonObject["task"]!!.jsonPrimitive.content)

        assertEquals(404, call("GET", "/know/k9999").statusCode())
        assertEquals(404, call("POST", "/know/k9999/verify").statusCode())
        assertEquals(400, call("GET", "/know/search").statusCode())
        assertContains(call("POST", "/know", """{"scope":"planet:mars","text":"x"}""").body(), "scope must start with")
        assertEquals(400, call("POST", "/know", """{"scope":"repo"}""").statusCode())
        assertEquals(400, call("POST", "/know", "not json").statusCode())
    }

    @Test
    fun `a batch answers one result per fact`() {
        val batch = json(call("POST", "/know", """{"facts":[{"scope":"repo","text":"first fact about billing"},{"scope":"repo","text":"second fact about invoices"}]}"""))
        assertEquals(2, Regex("\"action\":\"added\"").findAll(batch["results"].toString()).count())
    }

    @Test
    fun `an anchored fact turns stale when its file changes and clean again after verify`() {
        val put = json(call("POST", "/know", """{"scope":"symbol:$BILLING#Billing.total","text":"billing total returns the amount unchanged","root":${JsonPrimitive(repo.toString())}}"""))
        val id = put["fact"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(put["fact"]!!.jsonObject["anchors"].toString().contains("\"sig\":\""), "fingerprinted on write")
        assertEquals(0, json(call("GET", "/know/stale"))["stale"].toString().count { it == '{' })

        repo.resolve(BILLING).writeText("package demo\n\nclass Billing {\n    fun total(a: Int): Int = a + 1\n}\n")
        val stale = json(call("GET", "/know/stale"))
        assertEquals(1, stale["checked"]!!.jsonPrimitive.content.toInt())
        assertContains(stale["stale"].toString(), "STALE: body changed: $BILLING#Billing.total")
        assertContains(search("billing total"), "STALE: body changed")

        val verified = call("POST", "/know/$id/verify")
        assertEquals(200, verified.statusCode(), verified.body())
        assertFalse(search("billing total").contains("STALE"))
    }

    @Test
    fun `a file named without its directory is anchored and found by anchor, with or without a question`() {
        val put = json(call("POST", "/know", """{"scope":"area:billing","text":"Billing.kt is the one place that adds tax","root":${JsonPrimitive(repo.toString())}}"""))
        val id = put["fact"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertContains(put["fact"]!!.jsonObject["anchors"].toString(), "\"path\":\"$BILLING\"")

        assertContains(json(call("GET", "/know/search?anchor=Billing.kt"))["hits"].toString(), id)
        assertContains(json(call("GET", "/know/search?q=tax&anchor=$BILLING"))["hits"].toString(), id)
        assertEquals("[]", json(call("GET", "/know/search?anchor=Other.kt"))["hits"].toString())
        assertEquals(400, call("GET", "/know/search?anchor=%20").statusCode())
    }

    private companion object {
        const val BILLING = "src/main/kotlin/demo/Billing.kt"
    }
}
