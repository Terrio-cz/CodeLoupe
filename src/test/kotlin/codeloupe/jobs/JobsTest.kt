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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.net.http.HttpClient as JdkHttpClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobsTest {
    private val port = freePort()
    private val home = TestRepos.tmpDir("jobs-home")
    private val hookRecord = home.resolve("hook-calls.jsonl")
    private val config = config(home, port)
    private val daemon = Daemon.start(config, webhookBackoffMs = listOf(100, 200, 400))
    private val work = TestRepos.tmpDir("jobs-work")
    private val http = JdkHttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    @Test
    fun `a job runs in the daemon, independent of the client that started it`() {
        val job = start("print=Running FooTest", "print=FooTest > bar() FAILED", "print=5 tests completed, 2 failed, 1 skipped", "sleep=1500", "exit=3")
        val id = job.getValue("id").jsonPrimitive.content
        val running = waitFor(id) { it.status == JobStatus.RUNNING }
        val process = ProcessHandle.of(running.pid!!).orElseThrow()
        assertEquals(ProcessHandle.current().pid(), process.parent().map { it.pid() }.orElse(-1), "a child of the daemon, not of the client")
        val done = await(id).single()
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(3, done.exit)
        assertTrue(done.durationMs!! >= 1500)
        assertEquals(JobSummary(5, 2, 2, 1, listOf("FooTest > bar() FAILED"), listOf("Running FooTest", "FooTest > bar() FAILED", "5 tests completed, 2 failed, 1 skipped")), done.summary)
        assertContains(Files.readString(Path.of(done.log)), "FooTest > bar() FAILED")
        assertFalse(process.isAlive)
    }

    @Test
    fun `a job needing a busy slot waits in the daemon and starts when one frees`() {
        val ids = (1..3).map { start("marker=slot-test", "sleep=1500", slot = "pair").getValue("id").jsonPrimitive.content }
        waitFor(ids[2]) { true }
        val slot = daemon.status().jobs.slots.single { it.name == "pair" }
        assertEquals(2, slot.capacity)
        assertEquals(listOf(ids[2]), slot.waiting, "the third waits while two run")
        assertEquals(JobStatus.QUEUED, daemon.jobs.get(ids[2])!!.status)
        val done = ids.map { await(it).single() }
        assertTrue(done.all { it.ok })
        val firstEnd = done.take(2).minOf { Instant.parse(it.endedAt) }
        assertTrue(Instant.parse(done[2].startedAt) >= firstEnd, "started only after a holder ended")
        assertEquals(4, hookCalls().count { "marker=slot-test" in it }, "three submissions, and the queued one checked again before it starts")
    }

    @Test
    fun `the policy hook sees a Bash command and deny, ask or a broken hook never start the job`() {
        val before = daemon.jobs.list(500).size
        for ((marker, verdict) in listOf("deny-me" to "deny", "ask-me" to "ask", "crash-me" to "deny", "block-me" to "deny")) {
            val (status, body) = post("/jobs", request(FakeJob.command("marker=$marker", "touch=${work.resolve(marker)}")))
            assertEquals(403, status, marker)
            assertEquals(verdict, body.getValue("refused").jsonPrimitive.content, marker)
            assertFalse(Files.exists(work.resolve(marker)), "$marker never ran")
        }
        assertEquals(before, daemon.jobs.list(500).size, "refused jobs leave no record")
        val (_, blocked) = post("/jobs", request(FakeJob.command("marker=block-me")))
        assertEquals("blocked by exit code", blocked.getValue("reason").jsonPrimitive.content)

        val id = start("exit=0", env = mapOf("SECRET_X" to "it's here")).getValue("id").jsonPrimitive.content
        await(id)
        val input = hookCalls().last { "SECRET_X" in it }
        assertFalse(input.startsWith("env-leaked"), "the hook runs in the daemon's environment, not the caller's")
        val json = JsonFormat.json.parseToJsonElement(input).jsonObject
        assertEquals("PreToolUse", json.getValue("hook_event_name").jsonPrimitive.content)
        assertEquals("Bash", json.getValue("tool_name").jsonPrimitive.content)
        assertEquals(work.toString(), json.getValue("cwd").jsonPrimitive.content)
        val expected = CommandLine.withEnv(mapOf("SECRET_X" to "it's here"), FakeJob.command("exit=0"))
        assertEquals(expected, json.getValue("tool_input").jsonObject.getValue("command").jsonPrimitive.content)
        assertEquals(FakeJob.command("exit=0"), CommandLine.split(expected).drop(1), "Bash splits it back into exactly the argv that runs")
    }

    @Test
    fun `wait answers once, when the chain ends, and mirrors the exit code`() {
        val id = start("sleep=2000", "print=bye", "exit=4").getValue("id").jsonPrimitive.content
        val (_, early) = get("/jobs/$id/wait?timeoutSec=1")
        assertEquals("false", early.getValue("done").jsonPrimitive.content)
        val began = System.nanoTime()
        val (exit, text) = JobWaiter(DaemonClient(config)).wait(id)
        assertTrue(System.nanoTime() - began > 500_000_000, "blocked until the job ended")
        assertEquals(4, exit)
        assertContains(text, "$id done exit 4")
        assertContains(text, "last lines:\n  bye")
    }

    @Test
    fun `the CLI sends nothing to a daemon with another home on its port`() {
        val foreign = DaemonClient(config(TestRepos.tmpDir("other-home"), port))
        val error = kotlin.test.assertFailsWith<IllegalStateException> { foreign.send("GET", "/jobs") }
        assertContains(error.message!!, "is served by a daemon with home")
    }

    @Test
    fun `a passing chain runs its typed steps without waking anyone, a failing one wakes once`() {
        val since = daemon.events.lastSeq()
        val pass = start(
            "print=3 tests completed, 0 failed", "exit=0",
            then = listOf("failed==0 ? job:${CommandLine.join(FakeJob.command("print=second", "exit=0"))}", "notify:all green", "failed>0 ? notify:never"),
            onFailure = listOf("notify:broke"),
        ).getValue("id").jsonPrimitive.content
        val chain = await(pass)
        assertEquals(2, chain.size)
        assertEquals(pass, chain[1].parentId)
        assertTrue(chain.all { it.ok })
        val events = daemon.events.since(since, 1000).filter { it.data["rootId"]?.jsonPrimitive?.content == pass }
        assertEquals(
            listOf(EventTypes.JOB_STARTED, EventTypes.JOB_FINISHED, EventTypes.JOB_STARTED, EventTypes.JOB_FINISHED, EventTypes.JOB_NOTIFY),
            events.map { it.type },
        )
        val last = events.last { it.type == EventTypes.JOB_FINISHED }
        assertEquals("true", last.data.getValue("final").jsonPrimitive.content)
        assertEquals("false", last.data.getValue("wake").jsonPrimitive.content, "a passing chain wakes nobody")
        assertEquals("all green", events.last().data.getValue("message").jsonPrimitive.content)

        val fail = start("print=FooTest FAILED", "exit=1", then = listOf("notify:all green"), onFailure = listOf("notify:broke")).getValue("id").jsonPrimitive.content
        await(fail)
        val failEvents = daemon.events.since(since, 1000).filter { it.data["rootId"]?.jsonPrimitive?.content == fail }
        val finished = failEvents.single { it.type == EventTypes.JOB_FINISHED }.data
        assertEquals("true", finished.getValue("wake").jsonPrimitive.content)
        assertContains(finished.getValue("text").jsonPrimitive.content, "FooTest FAILED")
        assertEquals(listOf("broke"), failEvents.filter { it.type == EventTypes.JOB_NOTIFY }.map { it.data.getValue("message").jsonPrimitive.content })
    }

    @Test
    fun `a follow-up job is judged by the policy before it starts`() {
        val id = start("exit=0", then = listOf("job:${CommandLine.join(FakeJob.command("marker=deny-me", "touch=${work.resolve("follow")}"))}")).getValue("id").jsonPrimitive.content
        val chain = await(id)
        assertEquals(JobStatus.DENIED, chain.last().status)
        assertContains(chain.last().reason!!, "no deploys from tests")
        assertFalse(Files.exists(work.resolve("follow")))
        assertEquals(1, JobReport.exitCode(chain))
    }

    @Test
    fun `actions are typed - anything else is refused before the job starts`() {
        for (spec in listOf("rm -rf /", "bash:echo hi", "notify@slot:x", "job:", "failed=0 ? notify", "webhook:https://example.com/hook", "webhook:http://127.0.0.1:$port/shutdown")) {
            val (status, body) = post("/jobs", request(FakeJob.command("exit=0"), then = listOf(spec)))
            assertEquals(400, status, "$spec: $body")
        }
    }

    @Test
    fun `a running job can be cancelled with everything it started`() {
        val id = start("sleep=60000").getValue("id").jsonPrimitive.content
        val pid = waitFor(id) { it.status == JobStatus.RUNNING }.pid!!
        post("/jobs/$id/cancel", JsonObject(emptyMap()))
        val done = await(id).single()
        assertEquals(JobStatus.CANCELLED, done.status)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        val queued = (1..2).map { start("sleep=3000", slot = "solo").getValue("id").jsonPrimitive.content }
        post("/jobs/${queued[1]}/cancel", JsonObject(emptyMap()))
        assertEquals(JobStatus.CANCELLED, await(queued[1]).single().status)
        assertNull(await(queued[1]).single().startedAt, "never started")
        await(queued[0])
        assertTrue(daemon.status().jobs.slots.none { it.name == "solo" }, "a slot nobody holds or waits for is gone")
    }

    @Test
    fun `cancelling a chain ends its live job and starts no failure jobs`() {
        val since = daemon.events.lastSeq()
        val root = start(
            "exit=0",
            then = listOf("job:${CommandLine.join(FakeJob.command("sleep=60000"))}"),
            onFailure = listOf("notify:stopped", "job:${CommandLine.join(FakeJob.command("touch=${work.resolve("rollback")}"))}"),
        ).getValue("id").jsonPrimitive.content
        val follow = waitFor(root) { it.nextId != null }.nextId!!
        waitFor(follow) { it.status == JobStatus.RUNNING }
        post("/jobs/$root/cancel", JsonObject(emptyMap()))
        val chain = await(root)
        assertEquals(listOf(JobStatus.DONE, JobStatus.CANCELLED), chain.map { it.status })
        assertFalse(Files.exists(work.resolve("rollback")), "no job step after a cancel")
        assertTrue(daemon.events.since(since, 500).any { it.type == EventTypes.JOB_NOTIFY && it.data["rootId"]?.jsonPrimitive?.content == root })
    }

    @Test
    fun `a batch file never gets arguments cmd_exe would run as commands`() {
        assumeTrue(System.getProperty("os.name").lowercase().startsWith("windows"))
        Files.writeString(work.resolve("tool.cmd"), "@echo %*\r\n")
        val (status, body) = post("/jobs", request(listOf("./tool", "test&calc")))
        assertEquals(400, status)
        assertContains(body.getValue("error").jsonPrimitive.content, "batch file")
        val id = post("/jobs", request(listOf("./tool", "plain", "with space"))).second.getValue("job").jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(0, await(id).single().exit)
    }

    @Test
    fun `the job tool over MCP starts and reports`() = runBlocking {
        val client = Client(Implementation(name = "test", version = "0"))
        val transport = StreamableHttpClientTransport(HttpClient(CIO) { install(SSE) }, "http://127.0.0.1:$port/mcp") {
            headers.append(CodeLoupe.HEADER, "1")
        }
        client.connect(transport)
        assertTrue("job" in client.listTools().tools.map { it.name })
        val args = mapOf("action" to "start", "command" to FakeJob.command("print=hi"), "cwd" to work.toString())
        val started = (client.callTool("job", args).content.single() as TextContent).text
        val id = Regex("^(J\\S+)").find(started)!!.groupValues[1]
        assertContains(started, "codeloupe job wait $id")
        await(id)
        assertContains((client.callTool("job", mapOf("action" to "status", "id" to id)).content.single() as TextContent).text, "$id done exit 0")
        val denied = client.callTool("job", mapOf("action" to "start", "command" to FakeJob.command("marker=ask-me"), "cwd" to work.toString()))
        assertEquals(true, denied.isError)
        client.close()
    }

    @Test
    fun `a daemon that crashed leaves its jobs lost, their processes ended and their logs kept`() {
        val home2 = TestRepos.tmpDir("jobs-crash")
        val orphan = ProcessBuilder(FakeJob.command("sleep=60000")).start()
        val log = home2.resolve("jobs").also(Files::createDirectories).resolve("J1.log").also { Files.writeString(it, "line before the crash\n") }
        JobStore(home2.resolve("jobs.db")).use {
            it.put(
                JobRecord(
                    id = "J1", rootId = "J1", command = "sleep", cwd = work.toString(), status = JobStatus.RUNNING, createdAt = Instant.now().toString(),
                    startedAt = Instant.now().toString(), log = log.toString(), pid = orphan.pid(),
                    pidStart = orphan.info().startInstant().map(Instant::toString).orElse(null), wakeOn = Wake.FAILURE,
                ),
            )
        }
        val restarted = Daemon.start(config(home2, freePort()))
        try {
            val lost = restarted.jobs.get("J1")!!
            assertEquals(JobStatus.LOST, lost.status)
            assertEquals(listOf("line before the crash"), lost.summary!!.tail)
            assertTrue(orphan.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "the orphan was ended")
            assertTrue(Files.exists(log))
            val event = restarted.events.since(0, 100).single { it.type == EventTypes.JOB_FINISHED }
            assertEquals("lost", event.data.getValue("status").jsonPrimitive.content)
            assertEquals("true", event.data.getValue("wake").jsonPrimitive.content)
        } finally {
            restarted.stop()
        }
    }

    @Test
    fun `stopping a daemon with running jobs needs force and leaves them lost`() {
        val home3 = TestRepos.tmpDir("jobs-stop")
        val port3 = freePort()
        val first = Daemon.start(config(home3, port3))
        val id = runBlocking { first.jobs.submit(JobRequest(FakeJob.command("print=working", "sleep=60000"), work.toString())) }
            .let { (it as Submission.Accepted).job.id }
        val pid = waitFor(id, first) { it.status == JobStatus.RUNNING && "working" in runCatching { Files.readString(Path.of(it.log)) }.getOrDefault("") }.pid!!
        val refused = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port3/shutdown")).header(CodeLoupe.HEADER, "1").POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(409, refused.statusCode())
        first.stop()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        val second = Daemon.start(config(home3, port3))
        try {
            val lost = second.jobs.get(id)!!
            assertEquals(JobStatus.LOST, lost.status)
            assertEquals("daemon stopped", lost.reason)
            assertContains(Files.readString(Path.of(lost.log)), "working")
        } finally {
            second.stop()
        }
    }

    private fun config(home: Path, port: Int) = Config(
        home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null,
        jobs = JobsConfig(slots = mapOf("pair" to 2), policyHook = FakePolicyHook.command(hookRecord), policyTimeoutMs = 30_000),
    )

    private fun hookCalls(): List<String> = Files.readAllLines(hookRecord).filter { it.isNotBlank() }

    private fun request(
        command: List<String>,
        slot: String? = null,
        then: List<String> = emptyList(),
        onFailure: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ) = buildJsonObject {
        put("command", JsonArray(command.map(::JsonPrimitive)))
        put("cwd", work.toString())
        slot?.let { put("slot", it) }
        put("then", JsonArray(then.map(::JsonPrimitive)))
        put("onFailure", JsonArray(onFailure.map(::JsonPrimitive)))
        put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
    }

    private fun start(
        vararg args: String,
        slot: String? = null,
        then: List<String> = emptyList(),
        onFailure: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): JsonObject {
        val (status, body) = post("/jobs", request(FakeJob.command(*args), slot, then, onFailure, env))
        assertEquals(200, status, body.toString())
        return body.getValue("job").jsonObject
    }

    private fun await(id: String) = runBlocking { daemon.jobs.await(id, 60_000) }.also { chain ->
        assertTrue(chain.last().status.terminal, "chain of $id ended: $chain")
    }

    private fun waitFor(id: String, owner: Daemon = daemon, condition: (JobRecord) -> Boolean): JobRecord {
        repeat(200) {
            owner.jobs.get(id)?.takeIf(condition)?.let { return it }
            Thread.sleep(50)
        }
        return assertNotNull(owner.jobs.get(id)?.takeIf(condition), "job $id never reached the expected state")
    }

    private fun post(path: String, body: JsonObject): Pair<Int, JsonObject> = send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.ofString(body.toString())),
    )

    private fun get(path: String): Pair<Int, JsonObject> = send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET())

    private fun send(request: HttpRequest.Builder): Pair<Int, JsonObject> {
        val response = http.send(request.header(CodeLoupe.HEADER, "1").header("content-type", "application/json").build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to JsonFormat.json.parseToJsonElement(response.body()).jsonObject
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
