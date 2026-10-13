package codeloupe.jobs

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.cli.DaemonClient
import codeloupe.cli.JobWaiter
import codeloupe.config.Config
import codeloupe.config.JobsConfig
import codeloupe.daemon.Daemon
import codeloupe.events.EventTypes
import codeloupe.secrets.KeyProtector
import codeloupe.secrets.PassphraseProtector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.http.HttpClient as JdkHttpClient

/** Slots at runtime: capacity without a restart, queue order, and a restart that keeps the queue (FM-32). */
class SlotQueueTest {
    private val work = TestRepos.tmpDir("slots-work")
    private val http = JdkHttpClient.newHttpClient()
    private val keys = PassphraseProtector("test".toCharArray(), iterations = 1_000)

    @Test
    fun `raising a slot at runtime starts the first waiting job within 2 s and lowering it ends nobody`() = withDaemon { daemon ->
        val ids = (1..3).map { submit(daemon, "sleep=60000", slot = "gate") }
        waitFor(daemon, ids[0]) { it.status == JobStatus.RUNNING }
        val began = System.nanoTime()
        val (status, body) = post(daemon, "/jobs/slots/gate", buildJsonObject { put("capacity", 2) })
        assertEquals(200, status, body.toString())
        waitFor(daemon, ids[1], timeoutMs = 2_000) { it.status == JobStatus.RUNNING }
        assertTrue(System.nanoTime() - began < 2_000_000_000, "started within 2 s")
        assertEquals(JobStatus.QUEUED, daemon.jobs.get(ids[2])!!.status, "only one place more")
        assertContains(Files.readString(daemon.config.home.resolve("config.json")), "\"gate\": 2")

        assertEquals(200, post(daemon, "/jobs/slots/gate", buildJsonObject { put("capacity", 1) }).first)
        val slot = daemon.status().jobs.slots.single { it.name == "gate" }
        assertEquals(1, slot.capacity)
        assertEquals(ids.take(2), slot.running, "a cut ends no running job")
        assertEquals(listOf(ids[2]), slot.waiting)
        assertTrue(ids.take(2).all { pid -> ProcessHandle.of(daemon.jobs.get(pid)!!.pid!!).map { it.isAlive }.orElse(false) })

        assertEquals(400, post(daemon, "/jobs/slots/gate", buildJsonObject { put("capacity", 0) }).first)
        assertEquals(400, post(daemon, "/jobs/slots/gate", JsonObject(emptyMap())).first)
        ids.forEach { daemon.jobs.cancel(it) }
    }

    @Test
    fun `an edit of config_json raises a slot without a restart, a broken file changes nothing`() = withDaemon { daemon ->
        val ids = (1..3).map { submit(daemon, "sleep=60000", slot = "edit") }
        waitFor(daemon, ids[0]) { it.status == JobStatus.RUNNING }
        val config = daemon.config.home.resolve("config.json")
        val began = System.nanoTime()
        Files.writeString(config, """{"slots": {"edit": 2, "typo": "two"}}""")
        waitFor(daemon, ids[1], timeoutMs = 2_000) { it.status == JobStatus.RUNNING }
        assertTrue(System.nanoTime() - began < 2_000_000_000, "started within 2 s")
        val slots = daemon.status().jobs.slots.associateBy { it.name }
        assertEquals(2, slots.getValue("edit").configured)
        assertEquals("\"two\"", slots.getValue("typo").invalid, "a misconfigured slot shows")

        Files.writeString(config, """{"slots": {"edit": 3""")
        Thread.sleep(1_500)
        assertEquals(2, daemon.status().jobs.slots.single { it.name == "edit" }.capacity, "half a file is not applied")
        assertEquals(JobStatus.QUEUED, daemon.jobs.get(ids[2])!!.status)
        ids.forEach { daemon.jobs.cancel(it) }
    }

    @Test
    fun `job move reorders the waiting jobs, never a running one, and says so in an event`() = withDaemon { daemon ->
        val blocker = submit(daemon, "sleep=60000", slot = "order")
        waitFor(daemon, blocker) { it.status == JobStatus.RUNNING }
        val (a, b, c) = (1..3).map { submit(daemon, "exit=0", slot = "order") }
        val since = daemon.events.lastSeq()
        assertEquals(listOf(c, a, b), waiting(post(daemon, "/jobs/$c/move", buildJsonObject { put("first", true) })))
        assertEquals(listOf(c, b, a), waiting(post(daemon, "/jobs/$a/move", buildJsonObject { put("after", b) })))
        assertEquals(listOf(b, c, a), waiting(post(daemon, "/jobs/$b/move", buildJsonObject { put("before", c) })))

        val running = post(daemon, "/jobs/$blocker/move", buildJsonObject { put("first", true) })
        assertEquals(400, running.first)
        assertContains(running.second.getValue("error").jsonPrimitive.content, "is running")
        assertEquals(404, post(daemon, "/jobs/J000000-000000-none/move", buildJsonObject { put("first", true) }).first)
        assertEquals(400, post(daemon, "/jobs/$a/move", buildJsonObject { put("before", blocker) }).first)
        assertEquals(400, post(daemon, "/jobs/$a/move", JsonObject(emptyMap())).first)
        assertEquals(listOf(b, c, a), daemon.status().jobs.slots.single { it.name == "order" }.waiting, "refused moves change nothing")

        val events = daemon.events.since(since, 100).filter { it.type == EventTypes.JOB_REORDERED }
        assertEquals(3, events.size)
        assertEquals(listOf(b, c, a), events.last().data.getValue("waiting").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("order", events.last().data.getValue("slot").jsonPrimitive.content)

        daemon.jobs.cancel(blocker)
        val started = listOf(b, c, a).map { id -> await(daemon, id).startedAt!!.let(Instant::parse) }
        assertEquals(started.sorted(), started, "they ran in the new order")
    }

    @Test
    fun `a restart keeps three queued jobs queued in their order and a waiter gets the real result`() {
        val home = TestRepos.tmpDir("slots-restart")
        val port = freePort()
        var daemon = start(home, port)
        try {
            val blocker = submit(daemon, "sleep=60000", slot = "line")
            waitFor(daemon, blocker) { it.status == JobStatus.RUNNING }
            val (q1, q2, q3) = listOf("exit=0", "exit=7", "exit=0").map { submit(daemon, "print=queued", "sleep=1500", it, slot = "line", env = mapOf("JOB_SECRET" to "v1")) }
            assertEquals(200, post(daemon, "/jobs/$q3/move", buildJsonObject { put("first", true) }).first)
            assertEquals(1, daemon.jobs.atRisk(), "only the running job would be lost by a stop")

            val current = AtomicReference(daemon)
            val waiter = CompletableFuture.supplyAsync { waitAcrossRestarts(current, q2) }
            Thread.sleep(500)
            daemon.stop()
            daemon = start(home, port)
            current.set(daemon)

            assertEquals(JobStatus.LOST, daemon.jobs.get(blocker)!!.status, "the running job ended with the daemon")
            val slot = daemon.status().jobs.slots.single { it.name == "line" }
            assertEquals(listOf(q3), slot.running, "the moved job is still first")
            assertEquals(listOf(q1, q2), slot.waiting, "the others in their order")

            val answer = waiter.get(60, TimeUnit.SECONDS)
            assertEquals(7, answer.getValue("exit").jsonPrimitive.int, "the waiter got the real exit code")
            assertContains(answer.getValue("text").jsonPrimitive.content, "$q2 done exit 7")
            val (exit, _) = JobWaiter(DaemonClient(config(home, port))).wait(q1)
            assertEquals(0, exit)
            val records = listOf(q3, q1, q2).map { daemon.jobs.get(it)!! }
            assertEquals(listOf(JobStatus.DONE, JobStatus.DONE, JobStatus.DONE), records.map { it.status })
            assertEquals(listOf(0, 0, 7), records.map { it.exit })
            val started = records.map { Instant.parse(it.startedAt) }
            assertEquals(started.sorted(), started, "run in the order they had")
            assertTrue(Files.readString(Path.of(records[0].log)).contains("queued"))
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `without a key store a queued job is at risk on a stop and lost after it`() {
        val home = TestRepos.tmpDir("slots-nokey")
        val port = freePort()
        val noKeys = object : KeyProtector {
            override val name = "none"

            override fun wrap(key: ByteArray): String = error("no key store")

            override fun unwrap(blob: String): ByteArray = error("no key store")
        }
        val daemon = Daemon.start(config(home, port), jobKeyProtector = noKeys)
        val queued = try {
            val blocker = submit(daemon, "sleep=60000", slot = "bare")
            waitFor(daemon, blocker) { it.status == JobStatus.RUNNING }
            submit(daemon, "exit=0", slot = "bare").also {
                assertEquals(2, daemon.jobs.atRisk(), "the running one and the queued one without a stored launch")
            }
        } finally {
            daemon.stop()
        }
        val second = start(home, port)
        try {
            val lost = second.jobs.get(queued)!!
            assertEquals(JobStatus.LOST, lost.status)
            assertEquals("daemon stopped", lost.reason)
        } finally {
            second.stop()
        }
    }

    /** What `job wait` does: long polls, sent again while the daemon is away. */
    private fun waitAcrossRestarts(daemon: AtomicReference<Daemon>, id: String): JsonObject {
        while (true) {
            val answer = try {
                get(daemon.get(), "/jobs/$id/wait?timeoutSec=2")
            } catch (e: IOException) {
                Thread.sleep(100)
                continue
            }
            if (answer.first == 200 && answer.second.getValue("done").jsonPrimitive.content == "true") return answer.second
            if (answer.first != 200) Thread.sleep(100)
        }
    }

    private fun withDaemon(body: (Daemon) -> Unit) {
        val daemon = start(TestRepos.tmpDir("slots-home"), freePort())
        try {
            body(daemon)
        } finally {
            daemon.stop()
        }
    }

    private fun start(home: Path, port: Int) = Daemon.start(config(home, port), jobKeyProtector = keys)

    private fun config(home: Path, port: Int) = Config(
        home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, jobs = JobsConfig(),
    )

    private fun submit(daemon: Daemon, vararg args: String, slot: String? = null, env: Map<String, String> = emptyMap()): String =
        runBlocking { daemon.jobs.submit(JobRequest(FakeJob.command(*args), work.toString(), env = env, slot = slot)) }
            .let { (it as Submission.Accepted).job.id }

    private fun await(daemon: Daemon, id: String): JobRecord = runBlocking { daemon.jobs.await(id, 60_000) }.single().also {
        assertTrue(it.status.terminal, "job $id ended: $it")
    }

    private fun waitFor(daemon: Daemon, id: String, timeoutMs: Long = 10_000, condition: (JobRecord) -> Boolean): JobRecord {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < until) {
            daemon.jobs.get(id)?.takeIf(condition)?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("job $id never reached the expected state within $timeoutMs ms: ${daemon.jobs.get(id)}")
    }

    private fun waiting(answer: Pair<Int, JsonObject>): List<String> {
        assertEquals(200, answer.first, answer.second.toString())
        return answer.second.getValue("waiting").jsonArray.map { it.jsonPrimitive.content }
    }

    private fun post(daemon: Daemon, path: String, body: JsonObject) =
        send(daemon, HttpRequest.newBuilder(uri(daemon, path)).POST(HttpRequest.BodyPublishers.ofString(body.toString())))

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
