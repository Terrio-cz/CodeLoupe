package codeloupe.write

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WriteConfig
import codeloupe.daemon.Daemon
import codeloupe.daemon.TestToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `edit` through the daemon: offered only when the write gate is open, a full write over HTTP. */
class EditToolDaemonTest {
    private val http = HttpClient.newHttpClient()

    private fun call(port: Int, tool: String, args: JsonObject): Pair<Int, JsonObject> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$tool")).header("content-type", "application/json").header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, TestToken.of(port))
        val response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(args.toString())).build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun daemon(mode: String): Pair<Daemon, Int> {
        val port = ServerSocket(0).use { it.localPort }
        val config = Config(TestRepos.tmpDir("home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, write = WriteConfig(mode = mode))
        return Daemon.start(config) to port
    }

    @Test
    fun `the tool is not offered until the gate is open`() {
        for (mode in listOf("auto", "off")) {
            val (daemon, port) = daemon(mode)
            try {
                val (status, body) = call(port, "edit", buildJsonObject { put("op", "delete") })
                assertEquals(404, status, mode)
                assertContains(body.toString(), "not on offer")
            } finally {
                daemon.stop()
            }
        }
    }

    @Test
    fun `with writes on, a declaration is replaced over the API and the answer is the next hash`() {
        val repo = TestRepos.fixtureRepo("write/kotlin")
        val (daemon, port) = daemon("on")
        try {
            val symbol = call(port, "symbol", buildJsonObject { put("root", repo.toString()); put("name", "Money.isZero") })
            val text = symbol.second["text"]!!.jsonPrimitive.content
            val hash = Regex("hash=([0-9a-f]{10})").find(text)!!.groupValues[1]
            val (status, body) = call(
                port, "edit",
                buildJsonObject {
                    put("root", repo.toString()); put("op", "replace"); put("name", "Money.isZero"); put("hash", hash)
                    put("code", "fun isZero() = cents == 0L\n")
                },
            )
            assertEquals(200, status)
            assertTrue(body["ok"]!!.jsonPrimitive.boolean, body.toString())
            assertContains(body["text"]!!.jsonPrimitive.content, "unchanged")
            val (_, refused) = call(
                port, "edit",
                buildJsonObject { put("root", repo.toString()); put("op", "replace"); put("name", "Money.isZero"); put("hash", "0000000000"); put("code", "fun isZero() = false") },
            )
            assertFalse(refused["ok"]!!.jsonPrimitive.boolean)
            assertContains(refused["text"]!!.jsonPrimitive.content, "hash mismatch")
            val (_, invalid) = call(port, "edit", buildJsonObject { put("root", repo.toString()); put("op", "explode") })
            assertFalse(invalid["ok"]!!.jsonPrimitive.boolean)
        } finally {
            daemon.stop()
        }
    }
}
