package codeloupe.processes

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.net.http.HttpRequest
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The daemon with a fixture repository and two worktrees, each with build tools running for it. The Gradle daemons are
 * fake JVMs that sit in a directory of their own, as real ones do between builds, and whose log under a Gradle user home
 * names the workspace they last built in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessesDaemonTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf("src/main/kotlin/demo/A.kt" to "package demo\n\nclass A\n"))
    private val worktrees = repo.resolveSibling(repo.fileName.toString().lowercase() + "-worktrees").createDirectories()
    private val released = worktrees.resolve("TER-71")
    private val kept = worktrees.resolve("TER-72")
    private val gradleHome = TestRepos.tmpDir("proc-gradle-home")
    private val port = ServerSocket(0).use { it.localPort }
    private val daemon = Daemon.start(
        Config(TestRepos.tmpDir("proc-home"), port, 60_000, 120_000, 512, null, workspaces = WorkspacesConfig(repos = listOf(WorkspacesConfig.Repo(repo.toString())), gradleUserHome = gradleHome.toString())),
    )
    private val http = HttpClient.newHttpClient()
    private val children = ChildProcesses()

    @AfterAll
    fun stop() {
        children.close()
        daemon.stop()
    }

    private fun get(path: String): JsonObject {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").GET().build()
        return Json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
    }

    private fun post(path: String, body: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())

    private fun entries(array: String, report: JsonObject): List<JsonObject> = (report[array] as JsonArray).map { it.jsonObject }

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `the build daemons and the memory of each workspace are visible, and the idle daemons of a released workspace are stopped`() {
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "TER-71", released.toString(), "main")
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "TER-72", kept.toString(), "main")
        val elsewhere = TestRepos.tmpDir("proc-elsewhere")
        val idle = children.gradleDaemon(elsewhere, gradleHome, released)
        val busy = children.gradleDaemon(elsewhere, gradleHome, released, busy = true)
        val keptDaemon = children.gradleDaemon(elsewhere, gradleHome, kept)
        val stranger = children.gradleDaemon(elsewhere, gradleHome, null)
        val worker = children.start(released, "worker.org.gradle.process.internal.worker.GradleWorkerMain")
        val shell = children.start(released, "an-editor-or-a-shell")
        listOf(idle, busy, keptDaemon, stranger, worker, shell).forEach { children.awaitListed(it) }

        val report = get("/processes")
        val byPid = entries("processes", report).associateBy { it["pid"]!!.jsonPrimitive.content.toLong() }
        assertEquals("last build", byPid.getValue(idle.pid()).text("via"))
        assertEquals("TER-71", byPid.getValue(idle.pid()).text("workspace"))
        assertEquals("false", byPid.getValue(idle.pid()).text("busy"))
        assertEquals("true", byPid.getValue(busy.pid()).text("busy"))
        assertEquals("TER-72", byPid.getValue(keptDaemon.pid()).text("workspace"))
        assertEquals("cwd", byPid.getValue(worker.pid()).text("via"))
        assertTrue(stranger.pid() !in byPid, "a daemon that never built in a workspace is in none")
        val ram = entries("workspaces", report).first { it.text("workspace") == "TER-71" }
        assertEquals(4, ram.text("processes").toInt(), "the idle and the busy daemon by their last build, the worker and the shell by their directory")
        assertTrue(ram.text("rssMb").toLong() > 0)

        val workspace = ((get("/workspaces?ram=1")["repos"] as JsonArray).first().jsonObject["workspaces"] as JsonArray).map { it.jsonObject }.first { it.text("name") == "TER-71" }
        assertEquals(4, workspace.text("processes").toInt())
        assertTrue(workspace.text("ramBytes").toLong() > 0)
        assertTrue(get("/workspaces")["repos"]!!.jsonArray.first().jsonObject["workspaces"]!!.jsonArray.all { it.jsonObject["ramBytes"] is JsonNull }, "memory is only read when asked for")

        val plan = entries("entries", get("/reconcile")).filter { it.text("kind") == "process" }
        assertTrue(plan.all { it.text("verdict") == "keep" }, "nothing is stopped while the workspaces are active: $plan")

        assertEquals(200, post("/workspaces/release", """{"target": "TER-71", "repo": "${repo.toString().replace('\\', '/')}"}""").statusCode())
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until && (idle.isAlive || worker.isAlive)) Thread.sleep(200)

        assertTrue(!idle.isAlive && !worker.isAlive, "the idle daemon and the worker of the released workspace are stopped")
        assertTrue(busy.isAlive, "a daemon Gradle marks busy is left to finish")
        assertTrue(shell.isAlive, "a process that is no build tool is only reported")
        assertTrue(keptDaemon.isAlive, "the daemon of the other, active workspace stays")
        assertTrue(stranger.isAlive, "a daemon that is in no workspace is never touched")
        val blocked = entries("entries", get("/reconcile")).first { it.text("key") == "process:${busy.pid()}:${byPid.getValue(busy.pid()).text("startMs")}" }
        assertEquals("auto", blocked.text("verdict"))
        assertEquals("Gradle marks it busy", blocked.text("lastError").substringAfter(": "))
    }
}
