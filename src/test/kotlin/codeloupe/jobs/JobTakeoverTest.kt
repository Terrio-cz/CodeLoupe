package codeloupe.jobs

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.cli.JobWaiter
import codeloupe.config.Config
import codeloupe.config.JobsConfig
import codeloupe.daemon.Daemon
import codeloupe.events.EventTypes
import codeloupe.secrets.PassphraseProtector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.net.http.HttpClient as JdkHttpClient

/** CL-244: on Windows a running job outlives a stop of its daemon and the next daemon takes it over, with its real exit and its steps. */
class JobTakeoverTest {
    private val work = TestRepos.tmpDir("takeover-work")
    private val http = JdkHttpClient.newHttpClient()
    private val keys = PassphraseProtector("test".toCharArray(), iterations = 1_000)

    @BeforeEach
    fun windowsOnly() = assumeTrue(System.getProperty("os.name").lowercase().startsWith("windows"), "jobs survive their daemon on Windows")

    @Test
    fun `a stop without force leaves a running job to the next daemon, which records its real exit and runs its steps`() {
        val home = TestRepos.tmpDir("takeover-stop")
        var daemon = start(home)
        try {
            val after = work.resolve("after-takeover")
            val id = submit(
                daemon, "print=working", "sleep=5000", "exit=7", slot = "land",
                onFailure = listOf("notify:seven", "job:${CommandLine.join(FakeJob.command("touch=$after"))}"),
            )
            val running = waitFor(daemon, id) { it.status == JobStatus.RUNNING && "working" in runCatching { Files.readString(Path.of(it.log)) }.getOrDefault("") }
            assertTrue(running.survivesRestart)
            assertEquals(0, daemon.jobs.atRisk(), "nothing a stop would lose")

            val current = AtomicReference(daemon)
            val waiter = CompletableFuture.supplyAsync {
                JobWaiter({ path -> get(current.get(), path.replace("timeoutSec=300", "timeoutSec=2")) }, retryMs = 100).wait(id)
            }
            val (status, body) = post(daemon, "/shutdown")
            assertEquals(200, status, "a stop needs no --force: $body")
            awaitStopped(daemon)
            assertTrue(alive(running.pid!!), "the job runs on without a daemon")

            daemon = start(home)
            current.set(daemon)
            assertEquals(JobStatus.RUNNING, daemon.jobs.get(id)!!.status, "taken over")
            assertEquals(listOf(id), daemon.status().jobs.slots.single { it.name == "land" }.running, "and holds its slot")
            val chain = runBlocking { daemon.jobs.await(id, 60_000) }
            assertEquals(listOf(JobStatus.DONE, JobStatus.DONE), chain.map { it.status }, chain.toString())
            assertEquals(listOf(7, 0), chain.map { it.exit }, "its real exit code, then the step after it")
            assertTrue(Files.exists(after), "the completion steps ran after the takeover")
            val notify = daemon.events.since(0, 200).single { it.type == EventTypes.JOB_NOTIFY && it.data["id"]?.jsonPrimitive?.content == id }
            assertEquals("seven", notify.data.getValue("message").jsonPrimitive.content)

            val (_, text) = waiter.get(60, TimeUnit.SECONDS)
            assertContains(text, "$id done exit 7", message = "a wait started before the restart reports the real end")
            assertFalse(Files.exists(home.resolve("jobs").resolve("$id.exit")), "the exit file goes with the job")
        } finally {
            daemon.stop(force = true)
        }
    }

    @Test
    fun `a job that ends while no daemon runs is finished by the next one from its exit file`() {
        val home = TestRepos.tmpDir("takeover-gap")
        var daemon = start(home)
        try {
            val after = work.resolve("after-gap")
            val id = submit(daemon, "sleep=1500", "exit=5", onFailure = listOf("job:${CommandLine.join(FakeJob.command("touch=$after"))}"))
            val pid = waitFor(daemon, id) { it.status == JobStatus.RUNNING }.pid!!
            daemon.stop()
            assertTrue(alive(pid), "the job outlived the stop")
            assertTrue(eventually(30_000) { !alive(pid) }, "and ended while no daemon ran")
            val exitFile = home.resolve("jobs").resolve("$id.exit")
            assertTrue(eventually(20_000) { ExitFile.read(exitFile) != null }, "the watcher wrote the exit code")

            daemon = start(home)
            val chain = runBlocking { daemon.jobs.await(id, 60_000) }
            assertEquals(listOf(JobStatus.DONE, JobStatus.DONE), chain.map { it.status }, chain.toString())
            assertEquals(5, chain.first().exit, "the real exit code, read from the file")
            assertTrue(Files.exists(after), "the completion steps ran")
            assertFalse(Files.exists(exitFile))
        } finally {
            daemon.stop(force = true)
        }
    }

    @Test
    fun `stop --force still ends a job the next daemon could have taken over`() {
        val home = TestRepos.tmpDir("takeover-force")
        var daemon = start(home)
        try {
            val id = submit(daemon, "print=working", "sleep=60000")
            val pid = waitFor(daemon, id) { it.status == JobStatus.RUNNING }.pid!!
            assertEquals(200, post(daemon, "/shutdown?force=1").first)
            awaitStopped(daemon)
            assertTrue(eventually(20_000) { !alive(pid) }, "the job ended with the daemon")

            daemon = start(home)
            val lost = daemon.jobs.get(id)!!
            assertEquals(JobStatus.LOST, lost.status)
            assertEquals("daemon stopped", lost.reason)
        } finally {
            daemon.stop(force = true)
        }
    }

    @Test
    fun `a job taken over can be cancelled with its tree`() {
        val home = TestRepos.tmpDir("takeover-cancel")
        var daemon = start(home)
        try {
            val id = submit(daemon, "sleep=60000")
            val pid = waitFor(daemon, id) { it.status == JobStatus.RUNNING }.pid!!
            daemon.stop()
            daemon = start(home)
            assertEquals(JobStatus.RUNNING, daemon.jobs.get(id)!!.status)
            daemon.jobs.cancel(id)
            val ended = runBlocking { daemon.jobs.await(id, 30_000) }.single()
            assertEquals(JobStatus.CANCELLED, ended.status)
            assertFalse(alive(pid))
        } finally {
            daemon.stop(force = true)
        }
    }

    private fun start(home: Path) = Daemon.start(
        Config(home, freePort(), queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, jobs = JobsConfig()),
        jobKeyProtector = keys,
    )

    private fun submit(daemon: Daemon, vararg args: String, slot: String? = null, onFailure: List<String> = emptyList()): String =
        runBlocking { daemon.jobs.submit(JobRequest(FakeJob.command(*args), work.toString(), slot = slot, onFailure = onFailure)) }
            .let { (it as Submission.Accepted).job.id }

    private fun waitFor(daemon: Daemon, id: String, condition: (JobRecord) -> Boolean): JobRecord {
        assertTrue(eventually(20_000) { daemon.jobs.get(id)?.let(condition) == true }, "job $id never reached the expected state: ${daemon.jobs.get(id)}")
        return daemon.jobs.get(id)!!
    }

    /** The `/shutdown` answer comes first and the stop runs after it: until then the daemon still owns its jobs. */
    private fun awaitStopped(daemon: Daemon) {
        assertTrue(eventually(20_000) { runCatching { get(daemon, "/status") }.isFailure && daemon.jobs.snapshot().let { it.running + it.queued == 0 } })
        Thread.sleep(500)
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun eventually(ms: Long, check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (check()) return true
            Thread.sleep(50)
        }
        return check()
    }

    private fun post(daemon: Daemon, path: String) = send(daemon, HttpRequest.newBuilder(uri(daemon, path)).POST(HttpRequest.BodyPublishers.noBody()))

    private fun get(daemon: Daemon, path: String) = send(daemon, HttpRequest.newBuilder(uri(daemon, path)).GET())

    private fun uri(daemon: Daemon, path: String) = URI("http://127.0.0.1:${daemon.config.port}$path")

    private fun send(daemon: Daemon, request: HttpRequest.Builder): Pair<Int, JsonObject> {
        val response = http.send(
            request.header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).header("content-type", "application/json").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to JsonFormat.json.parseToJsonElement(response.body()).jsonObject
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
