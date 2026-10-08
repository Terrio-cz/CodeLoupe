package codeloupe.reconcile

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseRoutesTest {
    private fun post(port: Int, body: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/workspaces/release")).header(CodeLoupe.HEADER, "1").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `a release names a worktree by directory or name, answers at once, and refuses the main worktree`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        val name = repo.fileName.toString()
        val trees = repo.resolveSibling(name.lowercase() + "-worktrees").createDirectories()
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-1", trees.resolve("CL-1").toString(), "main")
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-2", trees.resolve("CL-2").toString(), "main")
        val port = ServerSocket(0).use { it.localPort }
        val config = Config(TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null, workspaces = WorkspacesConfig(repos = listOf(WorkspacesConfig.Repo(repo.toString()))))
        val daemon = Daemon.start(config)
        try {
            fun json(r: HttpResponse<String>) = Json.parseToJsonElement(r.body()).jsonObject

            val started = System.nanoTime()
            val byName = post(port, """{"target":"CL-1"}""")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, byName.statusCode(), byName.body())
            assertEquals(name, json(byName)["repo"]!!.jsonPrimitive.content)
            assertEquals("CL-1", json(byName)["workspace"]!!.jsonPrimitive.content)
            assertTrue(elapsedMs < 1_000, "release took $elapsedMs ms")

            val dir = trees.resolve("CL-2").toString().replace('\\', '/')
            val byPath = post(port, buildJsonObject { put("target", dir) }.toString())
            assertEquals(200, byPath.statusCode(), byPath.body())
            assertEquals("CL-2", json(byPath)["workspace"]!!.jsonPrimitive.content)

            // The worktree is gone already (git cleanup ran first): the name still releases its resources.
            assertEquals("cl-9", json(post(port, """{"target":"cl-9"}""")).getValue("workspace").jsonPrimitive.content)

            val main = post(port, """{"target":"$name"}""")
            assertEquals(400, main.statusCode())
            assertContains(main.body(), "main worktree")
            assertEquals(400, post(port, "{}").statusCode())
        } finally {
            daemon.stop()
        }
    }
}
