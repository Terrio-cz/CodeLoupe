package codeloupe.uiapi

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.MetricsConfig
import codeloupe.daemon.Daemon
import codeloupe.tracker.RecordedYouTrack
import codeloupe.tracker.youtrack.HttpReply
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Tasks screens of the UI API, read from a mirror that a YouTrack fake filled. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiApiTasksTest {
    private val token = "perm:c2VjcmV0LXRva2Vu.NDctMQ==.Zm9vYmFyYmF6cXV4"
    private val fake = RecordedYouTrack()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val reply = when {
                exchange.requestHeaders.getFirst("Authorization") != "Bearer $token" -> HttpReply(401, """{"error":"Unauthorized"}""")
                exchange.requestMethod == "POST" -> fake.post(exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery, exchange.requestBody.readBytes().decodeToString())
                else -> fake.get(exchange.requestURI.rawPath + "?" + exchange.requestURI.rawQuery)
            }
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val home = TestRepos.tmpDir("ui-tracker-home").also { home ->
        val tokenFile = home.resolve("token.txt")
        Files.writeString(tokenFile, "YT_TOKEN=\"$token\"\n")
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
    private val daemon = Daemon.start(Config(home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, metrics = MetricsConfig(transcriptDirs = listOf(TestRepos.tmpDir("ui-transcripts").toString()))))
    private val http = HttpClient.newHttpClient()

    @AfterAll
    fun stop() {
        daemon.stop()
        server.stop(0)
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").build(), HttpResponse.BodyHandlers.ofString())

    private fun json(path: String): JsonObject {
        val r = get(path)
        assertEquals(200, r.statusCode(), r.body())
        return Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun load() {
        // The UI API never asks the tracker: the first tool call fills the mirror.
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/tasks")).header(CodeLoupe.HEADER, "1")
            .POST(HttpRequest.BodyPublishers.ofString("""{"query":"epic: CL-4","mode":"ready","root":"C:/work/a"}""")).build()
        assertTrue(http.send(request, HttpResponse.BodyHandlers.ofString()).body().contains("\"ok\":true"))
    }

    @Test
    fun `tasks come from the mirror with paging, filters and a detail`() {
        assertEquals("null", json("/ui-api/v1/tasks")["mirrorSyncedAt"].toString(), "nothing synced yet")
        load()

        val all = json("/ui-api/v1/tasks?limit=200")
        assertEquals(16, all["total"]!!.jsonPrimitive.content.toInt())
        assertNotNull(all["mirrorSyncedAt"]!!.jsonPrimitive.content)
        assertEquals("null", all["nextCursor"].toString())
        assertEquals(16, all["items"]!!.jsonArray.size)
        assertTrue(json("/ui-api/v1/nav")["openTasks"].toString().toInt() > 0)

        val first = json("/ui-api/v1/tasks?limit=5")
        assertEquals(5, first["items"]!!.jsonArray.size)
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        val second = json("/ui-api/v1/tasks?limit=5&cursor=$cursor")
        assertEquals(5, second["items"]!!.jsonArray.size)
        assertNotEquals(first["items"]!!.jsonArray[0], second["items"]!!.jsonArray[0])
        assertEquals(400, get("/ui-api/v1/tasks?cursor=@@@").statusCode())
        assertEquals(400, get("/ui-api/v1/tasks?limit=500").statusCode())

        assertEquals(0, json("/ui-api/v1/tasks?project=ZZ")["total"]!!.jsonPrimitive.content.toInt())
        val found = json("/ui-api/v1/tasks?q=CL-26")["items"] as JsonArray
        assertTrue(found.any { it.jsonObject["id"]!!.jsonPrimitive.content == "CL-26" })

        val detail = json("/ui-api/v1/tasks/cl-26")
        assertEquals("CL-26", detail["id"]!!.jsonPrimitive.content)
        assertTrue(detail["url"]!!.jsonPrimitive.content.endsWith("/issue/CL-26"))
        assertEquals(3, detail["criteria"]!!.jsonArray.size)
        assertTrue(detail["links"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "CL-4" })
        assertTrue(detail["fields"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == "State" })
        assertTrue(detail["activity"]!!.jsonArray.any { it.jsonObject["kind"]!!.jsonPrimitive.content == "created" })
        assertEquals(404, get("/ui-api/v1/tasks/CL-9999").statusCode())
        assertEquals(404, get("/ui-api/v1/tasks/ABC-1").statusCode())

        val settings = json("/ui-api/v1/settings")["youtrack"]!!.jsonArray.single().jsonObject
        assertEquals("true", settings["tokenConfigured"].toString())
        assertTrue(!json("/ui-api/v1/settings").toString().contains(token))
    }
}
