package codeloupe.workspace

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspacesTest {
    @Test
    fun `the config names repositories, their roots and the abandon age`() {
        val config = WorkspacesConfig.parse(
            Json.parseToJsonElement(
                """{"workspaces": {"abandonedDays": 30, "repos": ["/a/one", {"path": "/b/two", "roots": ["/b/trees"]}, {"roots": []}, 3]}}""",
            ).jsonObject,
        )
        assertEquals(30, config.abandonedDays)
        assertEquals(listOf(WorkspacesConfig.Repo("/a/one"), WorkspacesConfig.Repo("/b/two", listOf("/b/trees"))), config.repos)
        assertEquals(14, WorkspacesConfig.parse(Json.parseToJsonElement("{}").jsonObject).abandonedDays)
    }

    @Test
    fun `the daemon serves the registry of a configured repository and its sibling worktree directory`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        // `<repo name>-worktrees` beside the repository is a root without any configuration.
        val trees = repo.resolveSibling(repo.fileName.toString().lowercase() + "-worktrees").createDirectories()
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-1", trees.resolve("CL-1").toString(), "main")
        trees.resolve("CL-2").createDirectories().resolve("junk.txt").writeText("x")
        val port = ServerSocket(0).use { it.localPort }
        val config = Config(
            TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null,
            workspaces = WorkspacesConfig(listOf(WorkspacesConfig.Repo(repo.toString()))),
        )
        val daemon = Daemon.start(config)
        try {
            val http = HttpClient.newHttpClient()
            fun get(path: String) = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).build(), HttpResponse.BodyHandlers.ofString())

            val response = get("/workspaces?size=1")
            assertEquals(200, response.statusCode(), response.body())
            val list = JsonFormat.json.decodeFromString(WorkspaceList.serializer(), response.body())
            val one = list.repos.single()
            assertEquals("main", one.workspaces.first().role)
            assertEquals(listOf("CL-1", "CL-2"), one.workspaces.drop(1).map { it.name })
            assertEquals(mapOf("active" to 2, "landed" to 0, "abandoned" to 0, "orphan" to 1), one.counts)
            assertTrue(one.workspaces.single { it.name == "CL-2" }.sizeBytes!! > 0)
            assertEquals("CL-1", one.workspaces.single { it.name == "CL-1" }.taskId)

            // The same repository asked for by a path inside it, and the text a CLI prints.
            val inner = get("/workspaces?repo=" + URLEncoder.encode(repo.resolve("src").toString(), Charsets.UTF_8))
            assertEquals(1, JsonFormat.json.decodeFromString(WorkspaceList.serializer(), inner.body()).repos.size)
            val text = WorkspaceRender.text(list, setOf(WorkspaceState.ORPHAN))
            assertContains(text, "1 orphan")
            assertContains(text, "CL-2")
            assertFalse(text.contains("CL-1 "))

            val missing = JsonFormat.json.decodeFromString(WorkspaceList.serializer(), get("/workspaces?repo=" + URLEncoder.encode(repo.resolve("nope").toString(), Charsets.UTF_8)).body())
            assertTrue(missing.repos.isEmpty())
            assertContains(missing.problems.single(), "does not exist")
        } finally {
            daemon.stop()
        }
    }
}
