package codeloupe.secrets

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.events.Scrubber
import codeloupe.jobs.FakeJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.net.http.HttpClient as JdkHttpClient

/** Where a value may and may not appear: the tool, the CLI report, status, logs, errors, job output and its handle, and the process it is injected into. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnvSurfacesTest {
    private val secret = "hunter2-Very-Secret-Value-4711"
    private val wsSecret = "ws-scoped-secret-Value-0042"
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("env-home")
    private val store = SecretStore(home.resolve("secrets").resolve("vault.env"), PassphraseProtector("pw".toCharArray(), iterations = 1_000)).also {
        it.set("PROBE_SECRET", SecretScope.GLOBAL, secret, source = "C:/x/.env")
        it.set("WS_ONLY", SecretScope.workspace("C:/Work/Terrio"), wsSecret)
    }
    private val daemon = Daemon.start(
        Config(home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null),
        secretStore = store,
    )
    private val http = JdkHttpClient.newHttpClient()
    private val work = TestRepos.tmpDir("env-work")

    @AfterAll
    fun stop() = daemon.stop()

    private fun send(request: HttpRequest.Builder, token: String? = null): Pair<Int, String> {
        val builder = request.header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).header("content-type", "application/json")
        token?.let { builder.header(TOKEN_HEADER, it).header(USED_BY_HEADER, "youtrack-mcp") }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun tool(name: String, args: JsonObject): String =
        JsonFormat.json.parseToJsonElement(send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$name")).POST(HttpRequest.BodyPublishers.ofString(args.toString()))).second).jsonObject.getValue("text").jsonPrimitive.content

    private fun token(): String = Files.readString(home.resolve("secrets").resolve("api-token.env")).trim()

    @Test
    fun `the env tool and the CLI report list names, scopes, sources and use, never a value`() {
        val answer = tool("env", buildJsonObject { put("root", JsonPrimitive(work.toString())); put("workspace", JsonPrimitive("c:/work/terrio")) })
        assertContains(answer, "2 names (values are never shown):")
        assertContains(answer, "PROBE_SECRET  global  C:/x/.env")
        assertContains(answer, "WS_ONLY  workspace:c:/work/terrio  manual")
        assertFalse(secret in answer || wsSecret in answer)
        val globalOnly = tool("env", buildJsonObject { put("root", JsonPrimitive(work.toString())) })
        assertFalse("WS_ONLY" in globalOnly, "a workspace secret does not apply without its workspace")
        val report = SecretReport.lines(store.list()).joinToString("\n")
        assertContains(report, "PROBE_SECRET")
        assertFalse(secret in report || wsSecret in report)
        // There is no tool that returns a value: the catalog has `env` for names and nothing else touches the vault.
        assertContains(tool("env", buildJsonObject { put("root", JsonPrimitive(work.toString())); put("all", JsonPrimitive(true)) }), "2 names")
    }

    @Test
    fun `the token-guarded api hands values to a local caller and records the use`() {
        val url = URI("http://127.0.0.1:$port/env/values?workspace=C:/Work/Terrio&names=PROBE_SECRET,WS_ONLY")
        val refused = send(HttpRequest.newBuilder(url).GET())
        assertEquals(401, refused.first)
        assertFalse(secret in refused.second)
        assertEquals(401, send(HttpRequest.newBuilder(url).GET(), token = "not-the-token").first)
        val ok = send(HttpRequest.newBuilder(url).GET(), token = token())
        assertEquals(200, ok.first)
        val values = JsonFormat.json.parseToJsonElement(ok.second).jsonObject.getValue("values").jsonObject
        assertEquals(secret, values.getValue("PROBE_SECRET").jsonPrimitive.content)
        assertEquals(wsSecret, values.getValue("WS_ONLY").jsonPrimitive.content)
        assertEquals(listOf("youtrack-mcp"), store.list().first { it.name == "PROBE_SECRET" }.usedBy)
        assertTrue(store.list().all { it.lastUsed != null })
        assertEquals("{\"values\":{}}", send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/env/values?names=NOPE")).GET(), token = token()).second)
    }

    @Test
    fun `status, the daemon log and a restart's files never hold a value`() {
        val status = send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/status")).GET()).second
        assertFalse(secret in status || wsSecret in status)
        tool("env", buildJsonObject { put("root", JsonPrimitive(work.toString())) })
        val log = Files.readString(home.resolve("daemon.log"))
        assertFalse(secret in log || wsSecret in log)
        Files.walk(home).filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith("api-token.env") }.forEach { file ->
            if (file.fileName.toString().endsWith(".db") || file.fileName.toString().endsWith(".jsa")) return@forEach
            assertFalse(String(Files.readAllBytes(file), Charsets.ISO_8859_1).contains(secret), "value in $file")
        }
    }

    @Test
    fun `a job that prints a value has it masked in the summary, in run and behind its doc handle`() {
        val answer = tool("run", buildJsonObject {
            put("root", JsonPrimitive(work.toString()))
            put("command", JsonArray(FakeJob.command("print=token is $secret ok", *Array(30) { "print=line $it" }).map(::JsonPrimitive)))
            put("raw", JsonPrimitive(true))
        })
        assertFalse(secret in answer, answer)
        assertContains(answer, "token is *** ok")
        val long = tool("run", buildJsonObject {
            put("root", JsonPrimitive(work.toString()))
            put("command", JsonArray(FakeJob.command("print=error: bad $wsSecret", *Array(300) { "print=progress $it" }).map(::JsonPrimitive)))
        })
        assertFalse(wsSecret in long, long)
        val handle = Regex("doc path=(job:\\S+)").find(long)!!.groupValues[1]
        val digest = tool("doc", buildJsonObject { put("root", JsonPrimitive(work.toString())); put("path", JsonPrimitive(handle)) })
        assertFalse(wsSecret in digest, digest)
        val window = Regex("L\\d+-\\d+").find(digest)!!.value
        val text = tool("doc", buildJsonObject { put("root", JsonPrimitive(work.toString())); put("path", JsonPrimitive(handle)); put("section", JsonPrimitive(window)) })
        assertFalse(wsSecret in text || secret in text, text)
    }

    @Test
    fun `env run gives the child its variables and masks them in what it prints, exit code included`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = EnvRunner(store).run(EnvProbe.command(exit = 3), workspace = null, repository = null, out = PrintStream(out, true), err = PrintStream(err, true))
        assertEquals(3, exit)
        val printed = out.toString().replace("\r\n", "\n")
        assertContains(printed, "got=***")
        assertContains(printed, "length=${secret.length}", message = "the child really received the value")
        assertContains(err.toString(), "on stderr: ***")
        assertFalse(secret in printed || secret in err.toString())
        assertTrue(store.list().first { it.name == "PROBE_SECRET" }.usedBy.contains("env run: java.exe") || store.list().first { it.name == "PROBE_SECRET" }.usedBy.any { it.startsWith("env run:") })
        // Without the workspace, its scoped secret is not in the child's environment.
        val none = ByteArrayOutputStream()
        EnvRunner(store).run(EnvProbe.command(), workspace = null, repository = null, out = PrintStream(none, true), err = PrintStream(ByteArrayOutputStream(), true))
        assertFalse(wsSecret in none.toString())
    }

    @Test
    fun `a damaged or empty token file is no token and gets replaced`() {
        val file = home.resolve("secrets").resolve("api-token.env")
        val url = URI("http://127.0.0.1:$port/env/values?names=PROBE_SECRET")
        Files.writeString(file, "")
        assertEquals(401, send(HttpRequest.newBuilder(url).GET()).first, "an empty file is not a token anyone can present")
        assertEquals(401, send(HttpRequest.newBuilder(url).GET(), token = "x").first)
        val fresh = token()
        assertTrue(Regex("[0-9a-f]{64}").matches(fresh), "the file was made again")
        assertEquals(200, send(HttpRequest.newBuilder(url).GET(), token = fresh).first)
    }

    @Test
    fun `a stored value of several lines is masked line by line, as output is read`() {
        val before = Scrubber.knownValues
        try {
            Scrubber.knownValues = { listOf("first-line-of-the-key\nsecond-line-of-the-key") }
            assertEquals("a *** b", Scrubber.text("a second-line-of-the-key b"))
            assertEquals("a *** b", Scrubber.text("a first-line-of-the-key b"))
            assertEquals("CODELOUPE_PASSPHRASE=***", Scrubber.text("CODELOUPE_PASSPHRASE=correct-horse"))
        } finally {
            Scrubber.knownValues = before
        }
    }

    @Test
    fun `the scrubber masks stored values by content and leaves short ones alone`() {
        val before = Scrubber.knownValues
        try {
            Scrubber.knownValues = { listOf("tiny", "a-long-enough-secret-value") }
            assertEquals("x *** y", Scrubber.text("x a-long-enough-secret-value y"))
            assertEquals("x tiny y", Scrubber.text("x tiny y"), "under 6 characters would mask ordinary words")
            Scrubber.knownValues = { error("the vault cannot be opened") }
            assertEquals("plain", Scrubber.text("plain"), "a broken vault never breaks a log line")
        } finally {
            Scrubber.knownValues = before
        }
    }
}
