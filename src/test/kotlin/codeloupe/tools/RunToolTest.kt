package codeloupe.tools

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.JobsConfig
import codeloupe.daemon.Daemon
import codeloupe.jobs.FakeJob
import codeloupe.jobs.FakePolicyHook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.net.http.HttpClient as JdkHttpClient

/** `run` in a daemon: a job whose output comes back as a summary with a handle, and the handle read through `doc`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunToolTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("run-home")
    private val work = TestRepos.tmpDir("run-work")
    private val daemon = Daemon.start(
        Config(home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null,
            jobs = JobsConfig(policyHook = FakePolicyHook.command(home.resolve("hook-calls.jsonl")), policyTimeoutMs = 30_000)),
    )
    private val http = JdkHttpClient.newHttpClient()

    @AfterAll
    fun stop() = daemon.stop()

    private fun call(tool: String, args: JsonObject): String {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$tool")).POST(HttpRequest.BodyPublishers.ofString(args.toString()))
            .header(CodeLoupe.HEADER, "1").header("content-type", "application/json").build()
        val body = JsonFormat.json.parseToJsonElement(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
        return body.getValue("text").jsonPrimitive.content
    }

    private fun run(vararg print: String, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(), marker: String? = null): String =
        call("run", JsonObject(mapOf(
            "root" to JsonPrimitive(work.toString()),
            "command" to JsonArray((FakeJob.command(*print.map { "print=$it" }.toTypedArray(), *listOfNotNull(marker?.let { "marker=$it" }).toTypedArray())).map(::JsonPrimitive)),
        ) + extra))

    @Test
    fun `a long output comes back as a summary with its saving and a handle, and the handle serves the rest`() {
        val lines = (1..400).map { n -> if (n == 250) "error: disk full at step 250" else "progress step $n ok" }
        val answer = run(*lines.toTypedArray())
        val header = answer.lines().first()
        assertContains(header, "exit 0")
        assertContains(header, Regex("→ \\d+ chars \\(\\d+% less\\)"))
        val handle = Regex("doc path=(job:\\S+)").find(header)!!.groupValues[1]
        assertContains(answer, "error: disk full at step 250")
        assertContains(answer, "… ")
        assertTrue(answer.length < lines.joinToString("\n").length / 4, "${answer.length} chars")

        val digest = call("doc", buildJsonObject { put("root", JsonPrimitive(work.toString())); put("path", JsonPrimitive(handle)) })
        assertContains(digest, "errors 1: L248-254")
        assertContains(call("doc", buildJsonObject { put("root", JsonPrimitive(work.toString())); put("path", JsonPrimitive(handle)); put("section", JsonPrimitive("L248-254")) }), "progress step 251 ok")
        assertContains(call("doc", buildJsonObject { put("root", JsonPrimitive(work.toString())); put("path", JsonPrimitive("job:nope")) }), "no output kept")
    }

    @Test
    fun `a short output is returned as it is, raw returns everything, a refused command is not run`() {
        assertContains(run("hello"), "hello")
        assertFalse("→" in run("hello"))
        val lines = Array(300) { "line $it" }
        assertContains(run(*lines, extra = mapOf("raw" to JsonPrimitive(true))), "line 299")
        assertContains(run("x", marker = "deny-me"), "not started - the policy hook answered deny: no deploys from tests")
    }

    @Test
    fun `a command that runs long answers with its job instead of holding the call`() {
        val answer = call("run", JsonObject(mapOf(
            "root" to JsonPrimitive(work.toString()),
            "command" to JsonArray(FakeJob.command("sleep=4000").map(::JsonPrimitive)),
            "timeoutSec" to JsonPrimitive(1),
        )))
        assertContains(answer, "still running after 1s")
        assertContains(answer, "codeloupe job wait")
    }
}
