package codeloupe.tracker

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.tracker.youtrack.HttpReply
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The daemon with a tracker configured against a local YouTrack fake over HTTP: wiring, status and token secrecy. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TrackerDaemonTest {
    private val token = "perm:c2VjcmV0LXRva2Vu.NDctMQ==.Zm9vYmFyYmF6cXV4"
    private val fake = RecordedYouTrack()

    @Volatile
    private var failWith: Int? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val reply = when {
                auth != "Bearer $token" -> HttpReply(401, """{"error":"Unauthorized"}""")
                // A misbehaving proxy that echoes the request headers in its error page.
                failWith != null -> HttpReply(failWith!!, """{"error":"x","error_description":"echo: $auth"}""")
                exchange.requestHeaders.getFirst("Content-Type").orEmpty().startsWith("multipart/form-data") ->
                    upload(exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery, exchange.requestBody.readBytes())
                exchange.requestMethod == "POST" -> fake.post(exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery, exchange.requestBody.readBytes().decodeToString())
                else -> fake.get(exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery)
            }
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    /** The one file part of a multipart body, handed to the fake the way YouTrack would take it. */
    private fun upload(path: String, body: ByteArray): HttpReply {
        val text = String(body, Charsets.ISO_8859_1)
        val name = Regex("filename=\"([^\"]*)\"").find(text)!!.groupValues[1]
        val start = text.indexOf("\r\n\r\n") + 4
        val end = text.lastIndexOf("\r\n--")
        return fake.postFile(path, name, body.copyOfRange(start, end))
    }

    private val home = TestRepos.tmpDir("tracker-home").also { home ->
        val tokenFile = home.resolve("token.txt")
        Files.writeString(tokenFile, "OTHER=1\nYT_TOKEN=\"$token\"\n")
        Files.writeString(
            home.resolve("config.json"),
            buildJsonObject {
                put("trackers", Json.parseToJsonElement(
                    """[{"name":"local","url":"http://127.0.0.1:${server.address.port}","projects":["CL"],
                       "token":{"dotenv":${JsonPrimitive(tokenFile.toString())},"key":"YT_TOKEN"}}]""",
                ))
            }.toString(),
        )
    }
    private val port = ServerSocket(0).use { it.localPort }
    private val daemon = Daemon.start(Config(home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = "C:/shared/default"))
    private val http = HttpClient.newHttpClient()
    private val outputs = mutableListOf<String>()

    @AfterAll
    fun stop() {
        daemon.stop()
        server.stop(0)
    }

    private fun call(tool: String, vararg args: Pair<String, String>): JsonObject = callJson(tool, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    private fun callJson(tool: String, body: JsonObject): JsonObject {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$tool")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val text = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        outputs += text
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun text(o: JsonObject) = o["text"]!!.jsonPrimitive.content

    @Test
    @Order(1)
    fun `the first query waits for the initial load and answers from the mirror`() {
        val ready = call("tasks", "query" to "epic: CL-4", "mode" to "ready", "root" to "C:/work/a")
        assertTrue(ready["ok"]!!.jsonPrimitive.boolean, ready.toString())
        assertTrue(text(ready).startsWith("5 ready tasks:"), text(ready))
        val status = daemon.status().trackers.single()
        assertEquals(16, status.projects.single().issues)
        assertTrue(status.projects.single().lastSync != null)
    }

    @Test
    @Order(2)
    fun `issue reads are remembered per root`() {
        assertContains(text(call("issue", "id" to "CL-26", "root" to "C:/work/a")), "Criteria 0/3")
        assertTrue(text(call("issue", "id" to "cl-26", "root" to "C:\\work\\A\\")).startsWith("CL-26 unchanged"))
        assertContains(text(call("issue", "id" to "CL-26", "root" to "C:/work/b")), "Criteria 0/3")
        assertContains(text(call("issue", "id" to "ABC-1")), "no tracker mirrors the project of 'ABC-1'; mirrored: CL")
        call("issue", "id" to "CL-27")
        assertContains(text(call("issue", "id" to "CL-27")), "Criteria 0/2", message = "without root nothing is remembered, not even under the default root")
    }

    @Test
    @Order(3)
    fun `graph mode answers through the daemon`() {
        assertContains(text(call("tasks", "query" to "CL-26", "mode" to "graph")), "subtask of CL-4")
        assertContains(text(call("tasks", "query" to "CL-4", "mode" to "progress")), "11 tasks, 0 resolved")
    }

    @Test
    @Order(4)
    fun `tracker failures never show the token`() {
        failWith = 502
        try {
            assertEquals("error: YouTrack HTTP 502 on /api/issues/CL-999: echo: Bearer [redacted]", text(call("issue", "id" to "CL-999", "root" to "C:/work/c")))
        } finally {
            failWith = null
        }
    }

    @Test
    @Order(5)
    fun `update writes through the daemon and answers in one short line`() {
        val body = buildJsonObject {
            put("id", "cl-27")
            put("set", buildJsonObject { put("State", "Done") })
            put("comment", "Landed")
        }
        val reply = text(callJson("update", body))
        assertTrue(reply.matches(Regex("CL-27 State: .+→Done · \\+comment \\S+")), reply)
        assertTrue(reply.length <= 300)
        assertContains(text(call("issue", "id" to "CL-27", "root" to "C:/work/d")), "Done")
        assertEquals("nothing to write: pass set={Field: value}, comment=<text>, check=<criterion> and/or edit_criterion=<criterion> with to=<text>", text(call("update", "id" to "CL-27")))
        assertContains(text(call("update", "id" to "ABC-1", "comment" to "x")), "no tracker mirrors")
    }

    @Test
    @Order(6)
    fun `check, edit-criterion, attach and create write through the daemon`() {
        assertEquals("CL-27 ticked: Criteria checklist rendered compactly · now Done", text(call("update", "id" to "cl-27", "check" to "checklist rendered")))
        assertContains(text(call("issue", "id" to "CL-27")), "Criteria 1/2")
        assertEquals("CL-27 criterion now: Checklist is compact · now Done", text(call("update", "id" to "CL-27", "edit_criterion" to "compactly", "to" to "Checklist is compact")))

        val refused = call("update", "id" to "CL-26", "check" to "e")
        assertFalse(refused["ok"]!!.jsonPrimitive.boolean)
        assertContains(text(refused), "error: 'e' matches 3 open criteria")
        assertContains(text(call("update", "id" to "CL-27", "check" to "x", "edit_criterion" to "y", "to" to "z")), "two writes")
        assertContains(text(call("update", "id" to "CL-27", "edit_criterion" to "y")), "needs to=")

        val file = Files.writeString(home.resolve("evidence.md"), "all green\n")
        assertEquals("CL-27 attached evidence.md (10 B)", text(call("attach", "id" to "CL-27", "file" to file.toString())))
        assertEquals(listOf("evidence.md" to "all green\n"), fake.uploads.map { it.first to String(it.second) })
        assertContains(text(call("issue", "id" to "CL-27", "view" to "full")), "evidence.md")
        assertContains(text(call("attach", "id" to "CL-27", "file" to "relative.md")), "must be absolute")
        val secret = Files.writeString(home.resolve(".env"), "A=1")
        val secretReply = call("attach", "id" to "CL-27", "file" to secret.toString())
        assertFalse(secretReply["ok"]!!.jsonPrimitive.boolean)
        assertContains(text(secretReply), "looks like a secret")

        val created = text(call("create", "epic" to "CL-4", "summary" to "Made by the test", "description" to "## Context\nx", "type" to "Task", "priority" to "Normal"))
        assertTrue(created.matches(Regex("CL-\\d+ created under CL-4 · Task · Normal")), created)
        assertContains(text(call("tasks", "query" to "CL-4", "mode" to "graph")), "Made by the test")
        assertContains(text(call("create", "epic" to "ABC-1", "summary" to "x")), "no tracker mirrors")
    }

    @Test
    @Order(7)
    fun `the token is in no output, status, log or mirror file`() {
        val status = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/status")).build(), HttpResponse.BodyHandlers.ofString()).body()
        assertContains(status, "\"trackers\"")
        val files = listOf(home.resolve("daemon.log"), home.resolve("calls.jsonl")) + home.resolve("trackers").listDirectoryEntries()
        val texts = outputs + status + files.filter { Files.isRegularFile(it) }.map { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }
        val secret = token.substringAfter("perm:").substringBefore('.')
        texts.forEach { assertFalse(token in it || secret in it, it.take(300)) }
        assertTrue(home.resolve("daemon.log").readText().contains("tracker local CL synced"))
    }
}
