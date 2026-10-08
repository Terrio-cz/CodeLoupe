package codeloupe.ports

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.PortsConfig
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortRoutesTest {
    private fun call(port: Int, method: String, path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun freeBlock(): Int = (21_000..29_000 step 8).first { start -> runCatching { (start..start + 3).map { ServerSocket(it) }.forEach { it.close() } }.isSuccess }

    @Test
    fun `the daemon allocates, lists and frees ports, and a release frees the workspace's`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        val name = repo.fileName.toString()
        val trees = repo.resolveSibling(name.lowercase() + "-worktrees").createDirectories()
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-1", trees.resolve("CL-1").toString(), "main")
        val start = freeBlock()
        val port = ServerSocket(0).use { it.localPort }
        val config = Config(
            TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null,
            workspaces = WorkspacesConfig(repos = listOf(WorkspacesConfig.Repo(repo.toString())), ports = PortsConfig(start..start + 3)),
        )
        val daemon = Daemon.start(config)
        try {
            fun allocate(workspace: String, portName: String) = call(port, "POST", "/ports/allocate", """{"repo":"$name","workspace":"$workspace","name":"$portName"}""")

            val app = allocate("CL-1", "app")
            assertEquals(200, app.statusCode(), app.body())
            assertEquals(start, Json.parseToJsonElement(app.body()).jsonObject["port"]!!.jsonPrimitive.content.toInt())
            assertEquals(start, Json.parseToJsonElement(allocate("CL-1", "app").body()).jsonObject["port"]!!.jsonPrimitive.content.toInt())
            assertEquals(start + 1, Json.parseToJsonElement(allocate("CL-2", "app").body()).jsonObject["port"]!!.jsonPrimitive.content.toInt())
            assertEquals(400, call(port, "POST", "/ports/allocate", """{"repo":"$name"}""").statusCode())

            val report = JsonFormat.json.decodeFromString(PortReport.serializer(), call(port, "GET", "/ports").body())
            assertEquals("$start-${start + 3}", report.range)
            assertEquals(listOf(start, start + 1), report.allocations.map { it.allocation.port })
            assertEquals(2, JsonFormat.json.parseToJsonElement(call(port, "GET", "/status").body()).jsonObject["portAllocations"]!!.jsonPrimitive.content.toInt())

            assertEquals(200, call(port, "POST", "/workspaces/release", """{"target":"CL-1"}""").statusCode())
            assertEquals(listOf(start + 1), JsonFormat.json.decodeFromString(PortReport.serializer(), call(port, "GET", "/ports").body()).allocations.map { it.allocation.port })
            assertEquals("""{"freed":1}""", call(port, "POST", "/ports/free", """{"repo":"$name","workspace":"CL-2"}""").body())
            assertTrue(JsonFormat.json.decodeFromString(PortReport.serializer(), call(port, "GET", "/ports").body()).allocations.isEmpty())
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `without a range the daemon says so`() {
        val port = ServerSocket(0).use { it.localPort }
        val config = Config(TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null)
        val daemon = Daemon.start(config)
        try {
            val answer = call(port, "POST", "/ports/allocate", """{"repo":"R","workspace":"W","name":"app"}""")
            assertEquals(409, answer.statusCode())
            assertTrue(answer.body().contains("workspaces.ports.range"))
        } finally {
            daemon.stop()
        }
    }
}
