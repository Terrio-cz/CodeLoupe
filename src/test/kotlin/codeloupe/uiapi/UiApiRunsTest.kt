package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.BudgetsConfig
import codeloupe.config.Config
import codeloupe.config.MetricsConfig
import codeloupe.daemon.Daemon
import codeloupe.metrics.TranscriptBuilder
import codeloupe.metrics.TranscriptBuilder.Companion.args
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The runs, steps, gaps, overview and event feed of the UI API on a daemon that reads synthetic transcripts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UiApiRunsTest {
    private val project = TestRepos.tmpDir("runs-transcripts").resolve("p")
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("runs-home")
    private val hourAgo = Instant.now().minusSeconds(3600)
    private val daemon = Daemon.start(
        Config(
            home, port, 60_000, 120_000, 512, null, metrics = MetricsConfig(transcriptDirs = listOf(project.toString()), ingestTtlMs = 0),
            budgets = BudgetsConfig(dailyWeighted = 1_000, runWeighted = 5_000),
        ),
    )
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ui-api/v1/$path")).header(CodeLoupe.HEADER, "1").GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun json(path: String): JsonObject {
        val r = get(path)
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun items(path: String): List<JsonObject> = (json(path)["items"] as JsonArray).map { it.jsonObject }

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.number(key: String) = text(key).toLong()

    /** Polls until [path] answers with [count] runs: a call only starts the ingest, which finishes in the background. */
    private fun awaitRuns(count: Int, path: String = "runs?range=7d") {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && json(path).number("total") != count.toLong()) Thread.sleep(50)
        assertEquals(count.toLong(), json(path).number("total"))
    }

    @Test
    @Order(1)
    fun `with no transcripts the lists are empty and bad parameters are refused`() {
        assertEquals(0, json("runs")["items"]!!.jsonArray.size)
        assertEquals(0, json("gaps")["items"]!!.jsonArray.size)
        for (bad in listOf("runs?sort=nonsense", "runs?range=1y", "runs?limit=0", "runs?cursor=zzz", "gaps?range=1y", "events?since=x")) {
            assertEquals(400, get(bad).statusCode(), bad)
        }
        assertEquals(404, get("runs/12345").statusCode())
        assertEquals(404, get("runs/12345/steps").statusCode())
    }

    @Test
    @Order(2)
    fun `runs are listed with cost, share and budget flag, sorted, filtered and paged`() {
        TranscriptBuilder(hourAgo).prompt("small run").turn(tools = arrayOf(Triple("a", "Read", args("file_path" to "src/A.kt")))).result("a", "x".repeat(100)).write(project.resolve("s-small.jsonl"))
        TranscriptBuilder(hourAgo)
            .prompt("work on TER-5")
            .turn(output = 500, tools = arrayOf(Triple("a", "Bash", args("command" to "gradlew test"))))
            .result("a", "z".repeat(4000))
            .turn(output = 500, tools = arrayOf(Triple("b", "Read", args("file_path" to "src/B.kt"))))
            .result("b", "b".repeat(40))
            .write(project.resolve("s-big.jsonl"))
        val sub = project.resolve("s-big").resolve("subagents")
        Files.createDirectories(sub)
        Files.writeString(sub.resolve("agent-r.meta.json"), """{"agentType": "terrio-reviewer", "description": "review TER-5"}""")
        TranscriptBuilder(hourAgo).prompt("review").turn().write(sub.resolve("agent-r.jsonl"))
        awaitRuns(3)

        val byWeight = items("runs?sort=weighted")
        assertEquals(listOf(5210L, 130L, 130L), byWeight.map { it.number("weighted") })
        val big = byWeight.first()
        assertEquals("s-big", big.text("file"))
        assertEquals("TER-5", big.text("ter"))
        assertEquals("true", big.text("overBudget"), "5 210 is over the run budget of 5 000")
        assertEquals(2, big.number("turns"))
        assertEquals("work on TER-5", big.text("title"))
        assertTrue(big.text("toolResultShare").toDouble() > 0)
        assertEquals(listOf("false", "false"), byWeight.drop(1).map { it.text("overBudget") })

        val reviewer = items("runs?role=terrio-reviewer").single()
        assertEquals("subagent", reviewer.text("kind"))
        assertEquals("TER-5", reviewer.text("ter"))
        assertEquals(listOf("s-big"), items("runs?q=work%20on").map { it.text("file") })
        assertEquals(setOf("main", "terrio-reviewer"), json("runs")["roles"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())

        val first = json("runs?sort=turns&limit=2")
        assertEquals(3, first.number("total"))
        val next = first.text("nextCursor")
        val second = json("runs?sort=turns&limit=2&cursor=$next")
        assertEquals(1, second["items"]!!.jsonArray.size)
        assertTrue(second["nextCursor"] is JsonNull)
    }

    @Test
    @Order(3)
    fun `a run has its steps with what each cost, and its categories`() {
        val id = items("runs?sort=weighted").first().text("id")
        val detail = json("runs/$id")
        assertEquals(id, detail["run"]!!.jsonObject.text("id"))
        assertEquals(setOf("build_test", "code_read"), detail["categories"]!!.jsonArray.map { it.jsonObject.text("category") }.toSet())

        val steps = items("runs/$id/steps")
        assertEquals(listOf("Bash", "Read"), steps.map { it.text("tool") })
        assertEquals("gradlew test", steps[0].text("summary"))
        assertEquals("src/B.kt", steps[1].text("summary"))
        assertEquals(4_000, steps[0].number("carried"), "4 000 characters carried by the one later turn")
        assertEquals(2_100, steps[0].number("weighted"))
        assertEquals(listOf("Bash", "Read"), items("runs/$id/steps?sort=weighted").map { it.text("tool") })
        assertEquals(listOf("Bash", "Read"), items("runs/$id/steps?sort=chars").map { it.text("tool") })
        val page = json("runs/$id/steps?limit=1")
        assertEquals(1, page["items"]!!.jsonArray.size)
        assertEquals(2, page.number("total"))
        assertTrue(page.text("nextCursor").isNotEmpty())
        assertEquals(400, get("runs/$id/steps?sort=nope").statusCode())
        assertEquals(404, get("runs/abc").statusCode())
    }

    @Test
    @Order(4)
    fun `the overview adds the cost of today and the series from the transcripts`() {
        val overview = json("overview?range=24h")
        val kpis = overview["kpis"]!!.jsonObject
        val series = overview["costSeries"]!!.jsonArray
        assertEquals(24, series.size)
        assertEquals(1_000, overview["budget"]!!.jsonObject.number("dailyWeighted"))
        assertTrue(kpis.number("weightedRange") > 0)
        assertEquals(kpis.number("weightedRange"), series.sumOf { it.jsonObject.number("weighted") })
        assertTrue(overview["budget"]!!.jsonObject.number("usedToday") <= kpis.number("weightedRange"))
        assertEquals(7, json("overview?range=7d")["costSeries"]!!.jsonArray.size)
        assertEquals(400, get("overview?range=1y").statusCode())
        assertEquals(1_000, json("settings")["budgets"]!!.jsonObject.number("dailyWeighted"))
    }

    @Test
    @Order(5)
    fun `budget breaches and new gaps reach the event feed once`() {
        val before = json("events").number("lastSeq")
        TranscriptBuilder(hourAgo)
            .prompt("ask codeloupe")
            .turn(tools = arrayOf(Triple("a", "mcp__codeloupe__find", args("q" to "Order.handle(_)"))))
            .result("a", "no declaration Order.handle")
            .turn(tools = arrayOf(Triple("b", "Bash", args("command" to "rg handle src"))))
            .result("b", "src/Order.kt:3")
            .write(project.resolve("s-gaps.jsonl"))
        awaitRuns(4)
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && items("events?since=$before").size < 2) Thread.sleep(50)
        json("events")

        val fresh = items("events?since=$before")
        assertEquals(listOf("gap_new", "gap_new"), fresh.map { it.text("kind") }, "the day and the big run were announced earlier, a call does not repeat them")
        assertEquals("gaps", fresh.first()["ref"]!!.jsonObject.text("screen"))

        val all = items("events?since=0")
        val breaches = all.filter { it.text("kind") == "budget_breach" }
        assertEquals(setOf("Daily budget exceeded", "Run budget exceeded"), breaches.map { it.text("title") }.toSet())
        assertEquals(2, breaches.size)
        val run = breaches.first { it.text("title") == "Run budget exceeded" }
        assertEquals("runs", run["ref"]!!.jsonObject.text("screen"))
        assertEquals(items("runs?sort=weighted").first().text("id"), run["ref"]!!.jsonObject.text("id"))

        val gaps = json("gaps?range=7d")
        val rows = gaps["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("empty", "followup_read"), rows.map { it.text("reason") }.toSet())
        val fallback = rows.first { it.text("reason") == "followup_read" }
        assertEquals("rg", fallback.text("fallback"))
        assertEquals("s-gaps", fallback.text("session"))
        assertEquals("handle", fallback.text("target"))
        assertEquals(2, gaps["summary"]!!.jsonArray.size)
        assertEquals(1, items("gaps?reason=empty").size)
        assertEquals(0, items("gaps?tool=other").size)
        assertEquals(400, get("gaps?reason=nonsense").statusCode())
        assertEquals(2, json("nav?gapsSince=2020-01-01T00:00:00Z").number("newGaps"))
        assertEquals(0, json("nav").number("newGaps"))
        assertEquals(400, get("nav?gapsSince=yesterday").statusCode())
    }
}
