package codeloupe.workspace

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import codeloupe.daemon.TestToken
import codeloupe.reconcile.ReconcilePlan
import codeloupe.reconcile.ReconcileRun
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The read-only routes share one registry scan; decisions and everything that changes a workspace read afresh. */
class SharedScanDaemonTest {
    private val http = HttpClient.newHttpClient()

    private fun send(port: Int, method: String, path: String, body: String = ""): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, TestToken.of(port)).method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())

    private fun list(port: Int, query: String = "") = JsonFormat.json.decodeFromString(WorkspaceList.serializer(), send(port, "GET", "/workspaces$query").body())

    @Test
    fun `the four reads share a scan inside the window, and a run or a release sees the registry at once`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        val trees = repo.resolveSibling(repo.fileName.toString().lowercase() + "-worktrees").createDirectories()
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-1", trees.resolve("CL-1").toString(), "main")
        val port = ServerSocket(0).use { it.localPort }
        val config = Config(TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null, workspaces = WorkspacesConfig(listOf(WorkspacesConfig.Repo(repo.toString())), recentScanMs = 600_000))
        val daemon = Daemon.start(config)
        try {
            val first = list(port)
            assertEquals(listOf("CL-1"), first.repos.single().workspaces.drop(1).map { it.name })

            // A stray directory appears: reads inside the window still show the earlier scan, whichever route asks.
            trees.resolve("CL-2").createDirectories()
            val again = list(port)
            assertEquals(first.generatedAt, again.generatedAt)
            assertEquals(1, again.repos.single().workspaces.count { it.role == "worktree" })
            assertEquals(emptyList(), JsonFormat.json.decodeFromString(ReconcilePlan.serializer(), send(port, "GET", "/reconcile").body()).entries.filter { it.workspace == "CL-2" })

            // `size` and `repo` are not the shared question; they read afresh and leave the shared scan alone.
            assertEquals(listOf("CL-1", "CL-2"), list(port, "?size=1").repos.single().workspaces.drop(1).map { it.name })
            assertEquals(first.generatedAt, list(port).generatedAt)

            // A decision reads the registry afresh: the reconciler run plans the stray directory at once.
            val run = JsonFormat.json.decodeFromString(ReconcileRun.serializer(), send(port, "POST", "/reconcile/run", "{}").body())
            assertEquals(listOf("CL-2"), run.remaining.entries.mapNotNull { it.workspace })

            // A release drops the shared scan.
            assertEquals(200, send(port, "POST", "/workspaces/release", """{"target":"CL-1"}""").statusCode())
            assertNotEquals(first.generatedAt, list(port).generatedAt)
        } finally {
            daemon.stop()
        }
    }
}
