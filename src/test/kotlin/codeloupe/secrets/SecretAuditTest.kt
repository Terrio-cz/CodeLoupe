package codeloupe.secrets

import codeloupe.TestRepos
import codeloupe.config.SecretsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretAuditTest {
    private val secret = "audit-fake-secret-value-123456"
    private val dir = TestRepos.tmpDir("audit")
    private val log = dir.resolve("secrets").resolve("audit.log")
    private var now = Instant.parse("2026-01-01T10:00:00Z")
    private val audit = SecretAudit(log, clock = { now })
    private val store = SecretStore(dir.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000), clock = { now }, audit = audit)

    private fun tick(minutes: Long) {
        now = now.plusSeconds(minutes * 60)
    }

    @Test
    fun `reads, creations, rotations and removals are logged with name, scope and consumer and no value`() {
        store.set("TOKEN_A", SecretScope.GLOBAL, secret, source = "manual")
        tick(1)
        store.resolve(SecretStore.chain(), usedBy = "youtrack-mcp")
        tick(1)
        store.resolve(SecretStore.chain(), usedBy = "env run: docker")
        store.resolve(SecretStore.chain(), usedBy = "youtrack-mcp")
        tick(1)
        store.set("TOKEN_A", SecretScope.GLOBAL, "$secret-new", source = "manual")
        store.knownValues()
        store.resolve(SecretStore.chain())
        tick(1)
        assertTrue(store.remove("TOKEN_A", SecretScope.GLOBAL))

        val events = audit.recent(50)
        assertEquals(listOf("REMOVED", "ROTATED", "READ", "READ", "READ", "CREATED"), events.map { it.action.name }, "newest first; masking and an anonymous resolve are no consumer reads")
        assertEquals(listOf("store", "manual", "youtrack-mcp", "env run: docker", "youtrack-mcp", "manual"), events.map { it.consumer })
        assertTrue(events.all { it.name == "TOKEN_A" && it.scope == "global" })
        val text = Files.readString(log)
        assertFalse(secret in text, "no value in the log")
        assertEquals(6, text.lines().count { it.isNotBlank() })
    }

    @Test
    fun `consumers are counted per key and the filters narrow the list`() {
        store.set("A", SecretScope.GLOBAL, secret)
        store.set("B", SecretScope.workspace("c:/w"), secret)
        repeat(3) { store.resolve(SecretStore.chain("c:/w"), usedBy = "mcp-one"); tick(1) }
        store.resolve(SecretStore.chain("c:/w"), usedBy = "mcp-two", names = setOf("B"))
        val consumers = audit.consumers()
        assertEquals(listOf("mcp-two", "mcp-one"), consumers.getValue("B|workspace:c:/w").map { it.consumer }, "most recent reader first")
        assertEquals(listOf(1, 3), consumers.getValue("B|workspace:c:/w").map { it.reads }.sorted())
        assertEquals(listOf(3), consumers.getValue("A|global").map { it.reads })
        assertEquals(listOf("B"), audit.recent(50, name = "B", scope = "workspace:c:/w").map { it.name }.distinct())
        assertEquals(2, audit.recent(2).size)
    }

    @Test
    fun `the log is append only and rolls into a second file instead of losing the newest lines`() {
        val small = SecretAudit(dir.resolve("roll").resolve("audit.log"), clock = { now }, rollBytes = 400)
        repeat(12) { small.record(SecretAudit.Action.READ, "N$it", "global", "consumer-$it") }
        val current = Files.readAllLines(dir.resolve("roll").resolve("audit.log"))
        assertTrue(Files.exists(dir.resolve("roll").resolve("audit.log.1")))
        assertEquals("N11", small.recent(1).single().name, "the newest line survives a roll")
        assertTrue(current.size < 12)
        // A line written by hand that is not an event is skipped, not fatal.
        Files.writeString(dir.resolve("roll").resolve("audit.log"), "not json\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals("N11", small.recent(1).single().name)
    }

    @Test
    fun `a consumer name cannot break the line format`() {
        audit.record(SecretAudit.Action.READ, "X", "global", "evil\nname\t" + "y".repeat(200))
        val line = Files.readAllLines(log).single()
        val parsed = Json.parseToJsonElement(line) as JsonObject
        val consumer = (parsed.getValue("consumer") as JsonPrimitive).content
        assertEquals(80, consumer.length)
        assertContains(consumer, "evilname")
    }

    @Test
    fun `keys older than the configured age are flagged and zero turns the reminder off`() {
        store.set("OLD", SecretScope.GLOBAL, secret)
        tick(60 * 24 * 100)
        store.set("FRESH", SecretScope.GLOBAL, secret)
        val metas = store.list()
        val old = metas.single { it.name == "OLD" }
        val fresh = metas.single { it.name == "FRESH" }
        assertEquals(100, SecretAge.days(old, now))
        assertTrue(SecretAge.due(old, 90, now))
        assertFalse(SecretAge.due(fresh, 90, now))
        assertFalse(SecretAge.due(old, 0, now))
        val report = SecretReport.lines(metas, 90, now)
        assertContains(report.single { it.startsWith("OLD") }, "ROTATE: 100 days old (limit 90)")
        assertFalse("ROTATE" in report.single { it.startsWith("FRESH") })
        assertFalse(report.joinToString().contains(secret))
        // Rotating resets the age.
        tick(60)
        store.set("OLD", SecretScope.GLOBAL, "$secret-rotated")
        assertFalse(SecretAge.due(store.list().single { it.name == "OLD" }, 90, now))
    }

    @Test
    fun `the rotation age comes from config json and defaults to 90 days`() {
        assertEquals(90, SecretsConfig.parse(JsonObject(emptyMap())).rotationDays)
        assertEquals(30, SecretsConfig.parse(Json.parseToJsonElement("""{"secrets":{"rotationDays":30}}""") as JsonObject).rotationDays)
        assertEquals(0, SecretsConfig.parse(Json.parseToJsonElement("""{"secrets":{"rotationDays":0}}""") as JsonObject).rotationDays)
        assertEquals(90, SecretsConfig.parse(Json.parseToJsonElement("""{"secrets":{"rotationDays":-5}}""") as JsonObject).rotationDays)
    }
}
