package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.accounts.AccountsFile
import codeloupe.accounts.ProjectDirName
import codeloupe.config.Config
import codeloupe.config.MetricsConfig
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import codeloupe.metrics.TranscriptBuilder
import codeloupe.secrets.PassphraseProtector
import codeloupe.secrets.SecretScope
import codeloupe.secrets.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /ui-api/v1/accounts` and the Overview filtered by account, on a daemon that reads two Claude config directories. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsApiTest {
    private val token = "fake-yt-account-token-3141"
    private val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf("src/main/kotlin/demo/Billing.kt" to "package demo\n\nclass Billing {\n    fun total(a: Int): Int = a\n}\n"))
    private val claudeA = TestRepos.tmpDir("acct-a")
    private val claudeB = TestRepos.tmpDir("acct-b")
    private val home = TestRepos.tmpDir("acct-home")
    private val port = ServerSocket(0).use { it.localPort }
    private val hourAgo = Instant.now().minusSeconds(3600)
    private val store = SecretStore(home.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000)).also {
        it.set("YOUTRACK_TOKEN_TERRIO", SecretScope.GLOBAL, token)
    }

    init {
        // Account A worked in the fixture repository (two turns), account B in some other directory (one turn).
        TranscriptBuilder(hourAgo).prompt("a").turn().turn().write(claudeA.resolve("projects").resolve(ProjectDirName.of(repo.toRealPath().toString())).resolve("s1.jsonl"))
        TranscriptBuilder(hourAgo).prompt("b").turn().write(claudeB.resolve("projects").resolve("C--elsewhere-project").resolve("s2.jsonl"))
        Files.writeString(
            home.resolve(AccountsFile.FILE),
            JsonFormat.json.encodeToString(
                AccountsFile.serializer(),
                AccountsFile(
                    claude = listOf(AccountsFile.Claude("a", "Account A", claudeA.toString()), AccountsFile.Claude("b", "Account B", claudeB.toString()), AccountsFile.Claude("gone", "Missing", claudeA.resolveSibling("no-such-dir").toString())),
                    youtrack = listOf(
                        AccountsFile.Youtrack("terrio", "Terrio", "http://127.0.0.1:9", listOf("ter", "cl"), "YOUTRACK_TOKEN_TERRIO"),
                        AccountsFile.Youtrack("other", null, "http://127.0.0.1:9", listOf("X"), "YOUTRACK_TOKEN_OTHER"),
                    ),
                ),
            ),
        )
    }

    private val daemon = Daemon.start(
        Config(home, port, 60_000, 120_000, 512, null, metrics = MetricsConfig(transcriptDirs = listOf(TestRepos.tmpDir("acct-none").toString()), ingestTtlMs = 0), workspaces = WorkspacesConfig(repos = listOf(WorkspacesConfig.Repo(repo.toString())))),
        secretStore = store,
    )
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ui-api/v1/$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun json(path: String): JsonObject {
        val r = get(path)
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun tool(name: String, vararg args: Pair<String, String>) {
        val body = buildJsonObject { args.forEach { (k, v) -> put(k, v) } }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$name")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        assertTrue(http.send(request, HttpResponse.BodyHandlers.ofString()).body().contains("\"ok\":true"))
    }

    private fun awaitAccounts(): JsonObject {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            val view = json("accounts")
            if (view["claude"]!!.jsonArray.map { it.jsonObject }.all { it["id"]!!.jsonPrimitive.content == "gone" || it["weighted7d"]!!.jsonPrimitive.content.toLong() > 0 }) return view
            Thread.sleep(50)
        }
        return json("accounts")
    }

    @Test
    fun `every account is listed with its usage, windows and mirror, and no token anywhere`() {
        tool("find", "q" to "total", "root" to repo.toString())
        val body = awaitAccounts()
        val claude = body["claude"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("a", "b", "gone"), claude.keys.toList())
        assertEquals(listOf(true, false, false), claude.values.map { it["isDefault"]!!.jsonPrimitive.content.toBoolean() })
        assertEquals("Account A", claude.getValue("a")["label"]!!.jsonPrimitive.content)
        assertEquals(260L, claude.getValue("a")["weighted7d"]!!.jsonPrimitive.content.toLong(), "two turns of 130")
        assertEquals(130L, claude.getValue("b")["weighted7d"]!!.jsonPrimitive.content.toLong())
        assertEquals(0L, claude.getValue("gone")["weighted7d"]!!.jsonPrimitive.content.toLong())
        assertEquals("false", claude.getValue("gone")["exists"]!!.jsonPrimitive.content)
        assertEquals("true", claude.getValue("a")["exists"]!!.jsonPrimitive.content)
        assertEquals(1, claude.getValue("a")["windows"]!!.jsonPrimitive.content.toInt(), "the repository was queried just now and has a transcript folder under account A")
        assertEquals(0, claude.getValue("b")["windows"]!!.jsonPrimitive.content.toInt())
        assertTrue(claude.getValue("a")["lastUsedAt"]!!.jsonPrimitive.content.isNotEmpty())

        val youtrack = body["youtrack"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(setOf("terrio", "other"), youtrack.keys)
        assertEquals(listOf("TER", "CL"), youtrack.getValue("terrio")["projects"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", youtrack.getValue("terrio")["tokenConfigured"]!!.jsonPrimitive.content)
        assertEquals("false", youtrack.getValue("other")["tokenConfigured"]!!.jsonPrimitive.content)
        assertEquals("true", youtrack.getValue("terrio")["editable"]!!.jsonPrimitive.content)
        assertTrue(youtrack.getValue("terrio")["mirror"]!!.jsonObject["state"]!!.jsonPrimitive.content in setOf("syncing", "error"), "never synced: the first sync runs or failed against a closed port")

        assertFalse(token in body.toString(), "no token in the response")
        assertFalse(daemonLog().contains(token))
    }

    @Test
    fun `the overview can be narrowed to one account and refuses an unknown one`() {
        tool("find", "q" to "total", "root" to repo.toString())
        awaitAccounts()
        fun week(path: String) = json(path)["kpis"]!!.jsonObject["weightedRange"]!!.jsonPrimitive.content.toLong()
        assertEquals(390L, week("overview?range=7d"))
        assertEquals(260L, week("overview?range=7d&account=a"))
        assertEquals(130L, week("overview?range=7d&account=b"))
        assertEquals(0L, week("overview?range=7d&account=gone"))
        val filteredCalls = json("overview?range=7d&account=b")["toolCalls"]!!.jsonArray
        assertEquals(0, filteredCalls.size, "the find call came from account A's directory")
        assertTrue(json("overview?range=7d&account=a")["toolCalls"]!!.jsonArray.isNotEmpty())
        assertEquals(400, get("overview?account=nobody").statusCode())
    }

    private fun daemonLog(): String = Files.readString(home.resolve("daemon.log"))
}
