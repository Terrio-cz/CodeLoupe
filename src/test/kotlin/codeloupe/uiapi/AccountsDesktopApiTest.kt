package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.accounts.AccountsFile
import codeloupe.accounts.DesktopProfile
import codeloupe.config.Config
import codeloupe.config.MetricsConfig
import codeloupe.daemon.Daemon
import codeloupe.metrics.TranscriptBuilder
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
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two accounts that share one config directory are told apart by the Claude Desktop instance that started each session (CL-231). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsDesktopApiTest {
    private val shared = TestRepos.tmpDir("desktop-shared")
    private val desktopA = DesktopProfile(TestRepos.tmpDir("desktop-a"))
    private val desktopB = DesktopProfile(TestRepos.tmpDir("desktop-b"))
    private val home = TestRepos.tmpDir("desktop-home")
    private val port = ServerSocket(0).use { it.localPort }
    private val hourAgo = Instant.now().minusSeconds(3600)

    init {
        val project = shared.resolve("projects").resolve("C--work-project")
        TranscriptBuilder(hourAgo).prompt("a").turn().turn().usageReport(session = 30, weekly = 51, fable = 6, severity = "warning").write(project.resolve("sess-a.jsonl"))
        TranscriptBuilder(hourAgo).prompt("b").turn().usageReport(session = 12, weekly = 20, fable = 2).write(project.resolve("sess-b.jsonl"))
        TranscriptBuilder(hourAgo).prompt("c").turn().turn().turn().write(project.resolve("sess-unknown.jsonl"))
        desktopA.session("sess-a")
        desktopB.session("sess-b")
        Files.writeString(
            home.resolve(AccountsFile.FILE),
            JsonFormat.json.encodeToString(
                AccountsFile.serializer(),
                AccountsFile(
                    claude = listOf(
                        AccountsFile.Claude("a", "Account A", shared.toString(), desktopDir = desktopA.dir.toString(), email = "login-a@example.test"),
                        AccountsFile.Claude("b", "Account B", shared.toString(), desktopDir = desktopB.dir.toString()),
                    ),
                ),
            ),
        )
    }

    private val daemon = Daemon.start(Config(home, port, 60_000, 120_000, 512, null, metrics = MetricsConfig(transcriptDirs = listOf(TestRepos.tmpDir("desktop-none").toString()), ingestTtlMs = 0)))
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun json(path: String): JsonObject {
        val r = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ui-api/v1/$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun accounts(): Map<String, JsonObject> {
        val until = System.currentTimeMillis() + 20_000
        var claude = emptyMap<String, JsonObject>()
        while (System.currentTimeMillis() < until) {
            claude = json("accounts")["claude"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
            if (claude.values.all { it["weighted7d"]!!.jsonPrimitive.content.toLong() > 0 }) break
            Thread.sleep(50)
        }
        return claude
    }

    @Test
    fun `each account shows the usage of the sessions its desktop instance started, not of the directory they share`() {
        val claude = accounts()
        assertEquals(260L, claude.getValue("a")["weighted7d"]!!.jsonPrimitive.content.toLong(), "two turns of 130")
        assertEquals(130L, claude.getValue("b")["weighted7d"]!!.jsonPrimitive.content.toLong())
        assertEquals(260L, claude.getValue("a")["weighted5h"]!!.jsonPrimitive.content.toLong())
        assertEquals(130L, claude.getValue("b")["weighted5h"]!!.jsonPrimitive.content.toLong())
        assertEquals(true, claude.getValue("a")["lastUsedAt"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("login-a@example.test", claude.getValue("a")["email"]!!.jsonPrimitive.content, "the e-mail the file names, not the one of the shared profile")
    }

    @Test
    fun `the overview and the limits can be narrowed to the account of a desktop instance`() {
        accounts()
        fun week(path: String) = json(path)["kpis"]!!.jsonObject["weightedRange"]!!.jsonPrimitive.content.toLong()
        assertEquals(780L, week("overview?range=7d"), "the session no instance lists belongs to nobody")
        assertEquals(260L, week("overview?range=7d&account=a"))
        assertEquals(130L, week("overview?range=7d&account=b"))
        fun sessions(account: String) = json("limits?account=$account")["snapshots"]!!.jsonArray.map { it.jsonObject["session"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("sess-a"), sessions("a"))
        assertEquals(setOf("sess-b"), sessions("b"))
    }
}
