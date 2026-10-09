package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.accounts.AccountsFile
import codeloupe.config.Config
import codeloupe.config.MetricsConfig
import codeloupe.daemon.Daemon
import codeloupe.metrics.BaselineFixture
import codeloupe.metrics.BaselineStore
import codeloupe.metrics.TranscriptBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Savings against a baseline report in the daemon's home: per account, in total, and what the screens are told when there is none. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SavingsApiTest {
    private val claudeA = TestRepos.tmpDir("sav-a")
    private val claudeB = TestRepos.tmpDir("sav-b")
    private val claudeC = TestRepos.tmpDir("sav-c").also { Files.createDirectories(it.resolve("projects")) }
    private val home = TestRepos.tmpDir("sav-home")
    private val port = ServerSocket(0).use { it.localPort }
    private val hourAgo = Instant.now().minusSeconds(3600)
    private val baselineFile = home.resolve(BaselineStore.FILE)

    init {
        // Account A has one run of two turns (130 each), account B one run of one turn, account C nothing.
        TranscriptBuilder(hourAgo).prompt("a").turn().turn().write(claudeA.resolve("projects").resolve("C--proj-a").resolve("s1.jsonl"))
        TranscriptBuilder(hourAgo).prompt("b").turn().write(claudeB.resolve("projects").resolve("C--proj-b").resolve("s2.jsonl"))
        Files.writeString(
            home.resolve(AccountsFile.FILE),
            JsonFormat.json.encodeToString(
                AccountsFile.serializer(),
                AccountsFile(claude = listOf(AccountsFile.Claude("a", "Account A", claudeA.toString()), AccountsFile.Claude("b", "Account B", claudeB.toString()), AccountsFile.Claude("c", "Account C", claudeC.toString()))),
            ),
        )
    }

    private val daemon = Daemon.start(Config(home, port, 60_000, 120_000, 512, null, metrics = MetricsConfig(transcriptDirs = listOf(TestRepos.tmpDir("sav-none").toString()), ingestTtlMs = 0)))
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun json(path: String): JsonObject {
        val r: HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ui-api/v1/$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun JsonElement?.number(): Double = if (this == null || this is JsonNull) Double.NaN else jsonPrimitive.content.toDouble()

    private fun weighted(query: String) = json("overview?$query")["kpis"]!!.jsonObject["weightedRange"].number()

    /** The overview once the ingest has read the three turns (account C has none, so it is never waited for). */
    private fun overview(query: String = "range=7d"): JsonObject {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && weighted("range=7d") < 390.0) Thread.sleep(50)
        return json("overview?$query")
    }

    private fun saved(): Map<String, JsonElement?> = json("accounts")["claude"]!!.jsonArray.map { it.jsonObject }.associate { it["id"]!!.jsonPrimitive.content to it["savedPct7d"] }

    private fun baselineState(view: JsonObject) = view["baseline"]!!.jsonObject["state"]!!.jsonPrimitive.content

    private fun touch() = Files.setLastModifiedTime(baselineFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000 + changes++ * 5_000L))

    private var changes = 0

    @Test
    @Order(1)
    fun `without a baseline file the screens are told so and show no saving`() {
        val view = overview()
        assertEquals("none", baselineState(view))
        assertTrue(view["baseline"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("--baseline"))
        val kpis = view["kpis"]!!.jsonObject
        assertEquals(390.0, kpis["weightedRange"].number())
        assertEquals(0.0, kpis["baselineRange"].number())
        assertEquals(0.0, kpis["savedTokens"].number())
        assertTrue(kpis["savedPct"] is JsonNull)
        assertEquals("none", baselineState(json("accounts")))
        assertTrue(saved().values.all { it is JsonNull })
    }

    @Test
    @Order(2)
    fun `an account's saving is that of its own runs against the baseline, and an account with no runs has none`() {
        overview()
        BaselineFixture.write(baselineFile, BaselineFixture.report("baseline", Triple("main", 2, 600)))
        val accounts = json("accounts")
        assertEquals("ok", baselineState(accounts))
        assertEquals(2, accounts["baseline"]!!.jsonObject["runs"].number().toInt())
        // The mean main run cost 300: account A spent 260 (13.3 % under), B spent 130 (56.7 % under), C has no run.
        assertEquals(13.3, saved().getValue("a").number())
        assertEquals(56.7, saved().getValue("b").number())
        assertTrue(saved().getValue("c") is JsonNull, "an account without runs shows no saving, not 0 %")
    }

    @Test
    @Order(3)
    fun `the overview shows the baseline and the saving of everything, or of the account it is filtered by`() {
        val all = overview()
        assertEquals("ok", baselineState(all))
        val kpis = all["kpis"]!!.jsonObject
        assertEquals(600.0, kpis["baselineRange"].number())
        assertEquals(210.0, kpis["savedTokens"].number())
        assertEquals(35.0, kpis["savedPct"].number())
        assertEquals(1.0, all["baseline"]!!.jsonObject["coveredShare"].number())
        assertEquals(600.0, all["costSeries"]!!.jsonArray.sumOf { it.jsonObject["baseline"].number() }, 1.0)

        val a = json("overview?range=7d&account=a")["kpis"]!!.jsonObject
        assertEquals(listOf(300.0, 40.0, 13.3), listOf(a["baselineRange"].number(), a["savedTokens"].number(), a["savedPct"].number()))
        val c = json("overview?range=7d&account=c")
        assertTrue(c["kpis"]!!.jsonObject["savedPct"] is JsonNull)
        assertEquals(0.0, c["kpis"]!!.jsonObject["baselineRange"].number())
    }

    @Test
    @Order(4)
    fun `a baseline without the role of the runs compares nothing, and a broken file is reported`() {
        BaselineFixture.write(baselineFile, BaselineFixture.report("other", Triple("planner", 1, 900)))
        touch()
        val view = overview()
        assertEquals("ok", baselineState(view))
        assertEquals(0.0, view["baseline"]!!.jsonObject["coveredShare"].number())
        assertTrue(view["kpis"]!!.jsonObject["savedPct"] is JsonNull)
        assertEquals(0.0, view["kpis"]!!.jsonObject["baselineRange"].number())
        assertTrue(saved().values.all { it is JsonNull })

        Files.writeString(baselineFile, "{ nope")
        touch()
        assertEquals("unreadable", baselineState(json("accounts")))
    }
}
