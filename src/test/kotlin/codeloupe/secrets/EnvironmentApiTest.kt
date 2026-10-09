package codeloupe.secrets

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.SecretsConfig
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/ui-api/v1/environment` and its audit: names, scopes, consumers and age from a real vault, never a value. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnvironmentApiTest {
    private val fresh = "env-api-fake-fresh-value-111"
    private val stale = "env-api-fake-stale-value-222"
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("env-api")
    private val vault = home.resolve("secrets").resolve("vault.env")
    private val protector = PassphraseProtector("pw".toCharArray(), iterations = 1_000)
    private val audit = SecretAudit(home.resolve("secrets").resolve("audit.log"))
    private val store = SecretStore(vault, protector, audit = audit).also {
        it.set("FRESH_TOKEN", SecretScope.GLOBAL, fresh, source = "app")
        it.set("WS_KEY", SecretScope.workspace("C:/Work/Terrio"), fresh, source = "C:/old/.env")
    }.also {
        SecretStore(vault, protector, clock = { Instant.now().minusSeconds(120L * 86_400) }, audit = audit).set("STALE_TOKEN", SecretScope.repository("C:/Repo"), stale)
    }
    private val daemon = Daemon.start(Config(home, port, 60_000, 120_000, 512, null, secrets = SecretsConfig(rotationDays = 90)), secretStore = store)
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(path: String): JsonObject = JsonFormat.json.parseToJsonElement(get(path).also { assertEquals(200, it.statusCode(), it.body()) }.body()).jsonObject

    @Test
    fun `keys carry scope, source, consumers, last use, age and the rotation flag, and no value`() {
        val token = SecretAccess(home, preset = store).token()
        val read = get("/env/values?names=FRESH_TOKEN", mapOf(TOKEN_HEADER to token, USED_BY_HEADER to "youtrack-mcp"))
        assertEquals(200, read.statusCode())

        val view = get("/ui-api/v1/environment")
        assertFalse(fresh in view.body() || stale in view.body())
        val body = JsonFormat.json.parseToJsonElement(view.body()).jsonObject
        assertEquals(true, body.getValue("storeReady").jsonPrimitive.content.toBoolean())
        assertEquals(90, body.getValue("rotationDays").jsonPrimitive.content.toInt())
        val keys = body.getValue("keys").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
        assertEquals(setOf("FRESH_TOKEN", "WS_KEY", "STALE_TOKEN"), keys.keys)

        val freshKey = keys.getValue("FRESH_TOKEN")
        assertEquals("global", freshKey.getValue("scope").jsonPrimitive.content)
        assertEquals("store", freshKey.getValue("source").jsonPrimitive.content)
        assertTrue("youtrack-mcp" in freshKey.getValue("consumers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", freshKey.getValue("rotationDue").jsonPrimitive.content)
        assertTrue(freshKey.getValue("lastUsedAt").jsonPrimitive.content.isNotEmpty())

        val ws = keys.getValue("WS_KEY")
        assertEquals("workspace", ws.getValue("scope").jsonPrimitive.content)
        assertEquals("c:/work/terrio", ws.getValue("scopeRef").jsonPrimitive.content)
        assertEquals("file", ws.getValue("source").jsonPrimitive.content)
        assertEquals("C:/old/.env", ws.getValue("sourceRef").jsonPrimitive.content)

        val old = keys.getValue("STALE_TOKEN")
        assertEquals("repo", old.getValue("scope").jsonPrimitive.content)
        assertEquals("true", old.getValue("rotationDue").jsonPrimitive.content)
        assertTrue(old.getValue("ageDays").jsonPrimitive.content.toLong() >= 119)
    }

    @Test
    fun `the audit resource lists reads and changes, can be filtered and holds no value`() {
        val token = SecretAccess(home, preset = store).token()
        get("/env/values?names=FRESH_TOKEN", mapOf(TOKEN_HEADER to token, USED_BY_HEADER to "script-one"))
        val all = get("/ui-api/v1/environment/audit")
        assertFalse(fresh in all.body() || stale in all.body())
        val events = (JsonFormat.json.parseToJsonElement(all.body()).jsonObject.getValue("events") as JsonArray).map { it.jsonObject }
        val actions = events.map { it.getValue("action").jsonPrimitive.content }
        assertTrue("created" in actions && "read" in actions, actions.toString())
        val onlyRead = json("/ui-api/v1/environment/audit?name=FRESH_TOKEN&limit=1")["events"]!!.jsonArray
        assertEquals(1, onlyRead.size)
        assertEquals("script-one", onlyRead.single().jsonObject.getValue("consumer").jsonPrimitive.content)
        assertEquals(400, get("/ui-api/v1/environment/audit?limit=0").statusCode())
        assertEquals(400, get("/ui-api/v1/environment/audit?limit=9999").statusCode())
    }
}
