package codeloupe.reconcile

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.AdoptionRule
import codeloupe.config.Config
import codeloupe.config.ProtectRule
import codeloupe.config.ReconcileConfig
import codeloupe.config.WorkspacesConfig
import codeloupe.daemon.Daemon
import codeloupe.daemon.TestToken
import codeloupe.docker.DockerObject
import codeloupe.docker.DockerTestSupport
import codeloupe.docker.Ownership
import codeloupe.docker.ResourceKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against the local Docker Engine: `CODELOUPE_DOCKER_TESTS=1 ./gradlew test --tests '*ReconcileIntegrationTest'`. A fixture
 * repository has a landed workspace CL-1 and an active one CL-2; the Docker resources are all named `cltest-<random>…`, and
 * the daemon sees no other repository, so nothing else in the Engine can be planned, let alone removed.
 */
class ReconcileIntegrationTest {
    private val image = System.getenv("CODELOUPE_DOCKER_TEST_IMAGE") ?: "eclipse-temurin:25-jre-alpine"

    private class Fixture(val repo: Path, val repoName: String)

    private fun fixture(): Fixture {
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        TestRepos.git(repo, "commit", "-q", "--allow-empty", "-m", "CL-1 land it")
        val trees = repo.resolveSibling(repo.fileName.toString().lowercase() + "-worktrees").createDirectories()
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-1", trees.resolve("CL-1").toString(), "main")
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-2", trees.resolve("CL-2").toString(), "main")
        TestRepos.git(trees.resolve("CL-2"), "commit", "-q", "--allow-empty", "-m", "CL-2 work in progress")
        return Fixture(repo, repo.fileName.toString())
    }

    private fun config(f: Fixture, name: String, auto: Boolean): Config {
        val workspaces = WorkspacesConfig(
            repos = listOf(WorkspacesConfig.Repo(f.repo.toString())),
            adoption = listOf(AdoptionRule(f.repoName, Regex("^$name-adopted$"), "CL-1")),
            reconcile = ReconcileConfig(auto = auto, graceMinutes = 0, protect = listOf(ProtectRule(Regex("^$name-keep$")))),
        )
        return Config(TestRepos.tmpDir("home"), ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null, workspaces = workspaces)
    }

    private fun http(config: Config, method: String, path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${config.port}$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, TestToken.of(config.port))
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun names(docker: DockerTestSupport, name: String): Set<String> =
        docker.api.snapshot().filter { it.names.any { n -> n.startsWith(name) } }.flatMap { it.names }.toSet()

    @Test
    fun `a landed workspace's labelled resources go, everything else stays unless confirmed`() {
        val f = fixture()
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_TESTS", repo = f.repoName, workspace = "CL-1") ?: return
        val name = "cltest-" + java.util.UUID.randomUUID().toString().take(8)
        val active = Ownership(f.repoName, "CL-2", "CL-2")
        try {
            docker.createContainer("$name-c1", image, docker.ownership)
            docker.createNetwork("$name-n1", docker.ownership)
            docker.createVolume("$name-v1", docker.ownership)
            docker.createVolume("$name-keep", docker.ownership)
            docker.createVolume("$name-active", active)
            docker.createUnlabelledVolume("$name-adopted")
            docker.createUnlabelledVolume("$name-unowned")
            val config = config(f, name, auto = false)
            val daemon = Daemon.start(config)
            try {
                val plan = JsonFormat.json.decodeFromString(ReconcilePlan.serializer(), http(config, "GET", "/reconcile").also { assertEquals(200, it.statusCode(), it.body()) }.body())
                val verdicts = plan.entries.filter { it.name.startsWith(name) }.associate { it.name to it.verdict }
                assertEquals(
                    mapOf(
                        "$name-c1" to Verdict.AUTO, "$name-n1" to Verdict.AUTO, "$name-v1" to Verdict.AUTO, "$name-keep" to Verdict.PROTECTED,
                        "$name-active" to Verdict.KEEP, "$name-adopted" to Verdict.CONFIRM,
                    ),
                    verdicts,
                )
                // The dry run changed nothing; the unowned volume is not in the plan at all.
                assertEquals(setOf("$name-c1", "$name-n1", "$name-v1", "$name-keep", "$name-active", "$name-adopted", "$name-unowned"), names(docker, name))
                assertTrue(plan.entries.none { it.name == "$name-unowned" })

                val run = JsonFormat.json.decodeFromString(ReconcileRun.serializer(), http(config, "POST", "/reconcile/run", "{}").also { assertEquals(200, it.statusCode(), it.body()) }.body())
                assertEquals(setOf("$name-c1", "$name-n1", "$name-v1"), run.actions.filter { it.outcome == ActionOutcome.REMOVED }.map { it.name }.toSet())
                assertEquals(setOf("$name-keep", "$name-active", "$name-adopted", "$name-unowned"), names(docker, name))

                // A protected or kept resource cannot be forced by naming it; the adopted one goes when named.
                val forced = JsonFormat.json.decodeFromString(
                    ReconcileRun.serializer(),
                    http(config, "POST", "/reconcile/run", """{"confirm":["volume:$name-keep","volume:$name-active","volume:$name-adopted","volume:$name-unowned"]}""").body(),
                )
                assertEquals(setOf("volume:$name-adopted"), forced.actions.filter { it.outcome == ActionOutcome.REMOVED }.map { it.key }.toSet())
                assertEquals(setOf("$name-keep", "$name-active", "$name-unowned"), names(docker, name))

                // Every action is in the journal.
                val journal = config.home.resolve("reconcile.jsonl").readText().lines().filter { it.isNotBlank() }
                assertTrue(journal.count { "\"removed\"" in it } >= 4, journal.toString())
                assertTrue(journal.any { "$name-adopted" in it })
            } finally {
                daemon.stop()
            }
        } finally {
            docker.cleanUp()
            listOf("keep", "active", "adopted", "unowned", "v1").forEach { docker.removeVolume("$name-$it") }
            docker.removeContainer("$name-c1")
            docker.removeNetwork("$name-n1")
        }
    }

    @Test
    fun `releasing an active workspace cleans it in the background with auto off, and nothing else`() = runBlocking {
        val f = fixture()
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_TESTS", repo = f.repoName, workspace = "CL-2") ?: return@runBlocking
        val name = "cltest-" + java.util.UUID.randomUUID().toString().take(8)
        val other = Ownership(f.repoName, "CL-1", "CL-1")
        try {
            docker.createContainer("$name-c2", image, docker.ownership)
            docker.createNetwork("$name-n2", docker.ownership)
            docker.createVolume("$name-v2", docker.ownership)
            docker.createVolume("$name-v1", other)
            docker.createUnlabelledVolume("$name-unowned")
            val config = config(f, name, auto = false)
            val daemon = Daemon.start(config)
            try {
                val started = System.nanoTime()
                val released = http(config, "POST", "/workspaces/release", """{"target":"CL-2"}""")
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(200, released.statusCode(), released.body())
                assertTrue(elapsedMs < 1_000, "release took $elapsedMs ms")

                val until = System.currentTimeMillis() + 60_000
                while (names(docker, name).any { it.endsWith("2") } && System.currentTimeMillis() < until) delay(500)
                assertEquals(setOf("$name-v1", "$name-unowned"), names(docker, name))
                // Nothing is left, so the mark is gone.
                val gone = System.currentTimeMillis() + 30_000
                while (!http(config, "GET", "/workspaces/releases").body().contains("\"items\":[]") && System.currentTimeMillis() < gone) delay(500)
                assertTrue(http(config, "GET", "/workspaces/releases").body().contains("\"items\":[]"))
            } finally {
                daemon.stop()
            }
        } finally {
            docker.cleanUp()
            listOf("v1", "v2", "unowned").forEach { docker.removeVolume("$name-$it") }
            docker.removeContainer("$name-c2")
            docker.removeNetwork("$name-n2")
        }
    }

    @Test
    fun `with auto on the daemon cleans the landed workspace by itself shortly after it starts`() = runBlocking {
        val f = fixture()
        val docker = DockerTestSupport.open("CODELOUPE_DOCKER_TESTS", repo = f.repoName, workspace = "CL-1") ?: return@runBlocking
        val name = "cltest-" + java.util.UUID.randomUUID().toString().take(8)
        try {
            docker.createVolume("$name-v1", docker.ownership)
            docker.createContainer("$name-c1", image, docker.ownership)
            val config = config(f, name, auto = true)
            val daemon = Daemon.start(config)
            try {
                val until = System.currentTimeMillis() + 90_000
                while (names(docker, name).isNotEmpty() && System.currentTimeMillis() < until) delay(1_000)
                assertEquals(emptySet(), names(docker, name))
                assertTrue(config.home.resolve("reconcile.jsonl").exists())
            } finally {
                daemon.stop()
            }
        } finally {
            docker.cleanUp()
            docker.removeVolume("$name-v1")
            docker.removeContainer("$name-c1")
        }
    }
}
