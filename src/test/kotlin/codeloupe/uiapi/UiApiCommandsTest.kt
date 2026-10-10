package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.commands.CommandAnalyzer
import codeloupe.commands.CommandFilter
import codeloupe.commands.CommandReport
import codeloupe.commands.CommandSort
import codeloupe.commands.CommandSource
import codeloupe.config.Config
import codeloupe.config.MetricsConfig
import codeloupe.daemon.Daemon
import codeloupe.ingest.TranscriptDb
import codeloupe.metrics.PriceTable
import codeloupe.metrics.TranscriptBuilder
import codeloupe.metrics.TranscriptBuilder.Companion.args
import kotlinx.serialization.json.Json
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
import kotlin.test.assertTrue

/** The Commands tab of the Runs screen on a daemon that reads synthetic transcripts: the same numbers as `codeloupe metrics commands` reads from the database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiApiCommandsTest {
    private val root = TestRepos.tmpDir("commands-api")
    private val project = root.resolve("transcripts").resolve("p")
    private val home = root.resolve("home")
    private val port = ServerSocket(0).use { it.localPort }
    private val hourAgo = Instant.now().minusSeconds(3600)
    private val daemon = Daemon.start(Config(home, port, 60_000, 120_000, 512, null, metrics = MetricsConfig(transcriptDirs = listOf(project.toString()), ingestTtlMs = 0)))
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() {
        daemon.stop()
        root.toFile().deleteRecursively()
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ui-api/v1/$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun json(path: String): JsonObject {
        val r = get(path)
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    @Test
    fun `the tab shows what the command report computes from the database, filtered by role and period`() {
        assertEquals(0, json("commands")["report"]!!.jsonObject["runs"]!!.jsonPrimitive.content.toInt(), "nothing ingested yet")
        TranscriptBuilder(hourAgo)
            .prompt("work on TER-5")
            .turn(tools = arrayOf(Triple("a", "Bash", args("command" to "cd /w && git diff --stat")), Triple("b", "Read", args("file_path" to "src/A.kt"))))
            .result("a", "z".repeat(4000)).result("b", "b".repeat(40))
            .turn(tools = arrayOf(Triple("c", "Bash", args("command" to "cd /w && git diff --stat"))))
            .result("c", "z".repeat(4000))
            .write(project.resolve("s-one.jsonl"))
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && json("commands")["report"]!!.jsonObject["runs"]!!.jsonPrimitive.content != "1") Thread.sleep(100)

        val view = json("commands?since=7d&top=5")
        val report = JsonFormat.json.decodeFromString(CommandReport.serializer(), view["report"]!!.toString())
        assertEquals(1, report.runs)
        assertEquals("git diff --stat", report.groups.first().command)
        assertEquals(2, report.groups.first().calls)
        assertEquals(1, report.groups.first().repeats)
        assertEquals(2000, report.groups.first().resultTokens)
        assertEquals(listOf("main"), view["roles"]!!.jsonArray.map { it.jsonPrimitive.content })

        TranscriptDb(home.resolve("transcripts.db")).use { db ->
            val direct = CommandAnalyzer(CommandSource(db), PriceTable.DEFAULT).report(CommandFilter(Instant.parse(report.since!!).toEpochMilli()), 5, CommandSort.COST)
            assertEquals(direct.copy(generatedAt = report.generatedAt), report, "the screen and the command read the same numbers")
        }

        assertEquals(0, json("commands?role=coder")["report"]!!.jsonObject["groups"]!!.jsonArray.size)
        assertEquals("git diff --stat", json("commands?sort=calls&top=1")["report"]!!.jsonObject["groups"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content)
        assertTrue(json("commands?since=2020-01-01")["report"]!!.jsonObject["runs"]!!.jsonPrimitive.content.toInt() >= 1)
        for (bad in listOf("commands?since=yesterday", "commands?top=0", "commands?top=101", "commands?sort=nope")) assertEquals(400, get(bad).statusCode(), bad)
    }
}
