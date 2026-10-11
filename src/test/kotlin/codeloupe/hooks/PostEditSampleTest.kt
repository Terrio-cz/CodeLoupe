package codeloupe.hooks

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.extension
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The false-positive rate of the post-edit check, measured on code that compiles: every Kotlin file of this project (main and test),
 * indexed as a repository of its own, is "edited" without change; whatever the check says about it is wrong. The card allows 2 %.
 * `CODELOUPE_POSTEDIT_SAMPLE=<directory>` measures the Kotlin files under another project instead (build output and hidden directories skipped).
 */
@OptIn(ExperimentalPathApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostEditSampleTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val home = TestRepos.tmpDir("clean-home")
    private val config = Config(home, port, queryTimeoutMs = 60_000, buildTimeoutMs = 300_000, buildHeapMb = 1024, defaultRoot = null)
    private val repo: Path = TestRepos.tmpDir("clean-repo").also { dir ->
        val sample = System.getenv(SAMPLE)?.let(Path::of)
        if (sample == null) {
            val project = Path.of(System.getProperty("user.dir"))
            for (part in listOf("src/main/kotlin", "src/test/kotlin")) {
                Files.createDirectories(dir.resolve(part).parent)
                project.resolve(part).copyToRecursively(dir.resolve(part), followLinks = false, overwrite = true)
            }
        } else {
            val sources = sample.walk().filter { it.extension == "kt" && sample.relativize(it).none { part -> part.toString() in SKIPPED } }.toList()
            for (file in sources) dir.resolve(sample.relativize(file).toString()).also { Files.createDirectories(it.parent) }.let { Files.copy(file, it) }
        }
        TestRepos.git(dir, "init", "-q", "-b", "main")
        TestRepos.git(dir, "add", "-A")
        TestRepos.git(dir, "commit", "-q", "-m", "project")
    }
    private val daemon = Daemon.start(config)
    private val http = HttpClient.newHttpClient()

    private companion object {
        const val SAMPLE = "CODELOUPE_POSTEDIT_SAMPLE"
        val SKIPPED = setOf("build", "out", "node_modules", ".git", ".gradle", ".idea")

        /** One file in this many is spoilt in the slips test. */
        const val STRIDE = 3
    }

    @AfterAll
    fun stop() = daemon.stop()

    private fun send(path: String, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("content-type", "application/json").header(CodeLoupe.HEADER, "1")
            .header(CodeLoupe.TOKEN_HEADER, daemon.token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun said(file: Path): String? {
        val body = buildJsonObject {
            put("hook_event_name", "PostToolUse")
            put("session_id", file.fileName.toString())
            put("cwd", repo.toString())
            put("tool_name", "Edit")
            put("tool_input", buildJsonObject { put("file_path", file.toString()) })
        }.toString()
        val response = send("/hook", body)
        if (response.statusCode() == 204) return null
        return Json.parseToJsonElement(response.body()).jsonObject["hookSpecificOutput"]!!.jsonObject["additionalContext"]!!.jsonPrimitive.content
    }

    private val files: List<Path> by lazy {
        // The first query makes the daemon index the repository.
        assertEquals(200, send("/api/find", """{"root":"${repo.toString().replace("\\", "/")}","q":"main"}""").statusCode())
        Files.writeString(home.resolve("config.json"), """{"hooks":{"postEdit":{"enabled":true,"timeoutMs":5000}}}""")
        repo.walk().filter { it.extension == "kt" }.sorted().toList()
    }

    @Test
    fun `code that compiles is reported on in at most 2 percent of its files`() {
        val reported = files.mapNotNull { file -> said(file)?.let { file to it } }
        val rate = reported.size * 100.0 / files.size
        println("post-edit false positives: ${reported.size} of ${files.size} files (${"%.2f".format(rate)} %)")
        daemon.status().hooks.let { println("post-edit latency over ${it.calls} calls: median ${it.medianMs} ms, p95 ${it.p95Ms} ms (passed: ${it.passed})") }
        reported.forEach { (file, text) -> println("  ${repo.relativize(file)}\n${text.lines().drop(1).joinToString("\n") { "    $it" }}") }
        assertTrue(files.size > 100, "the sample is the project's own sources or the directory in $SAMPLE: ${files.size} files")
        assertTrue(rate <= 2.0, "false positive rate ${"%.2f".format(rate)} % over ${files.size} files")
    }

    /**
     * The other side of the measure: a typical slip is put into a clean file, one at a time, and the check is asked whether it saw it.
     * A slip counts as seen when the answer names it (for the missing brace: any syntax error).
     */
    @Test
    fun `slips put into clean files are seen`() {
        for (slip in PostEditSlips.ALL) {
            var tried = 0
            var seen = 0
            for (file in files.filterIndexed { index, _ -> index % STRIDE == 0 }) {
                val original = Files.readString(file)
                val (spoilt, name) = slip.applyTo(original) ?: continue
                tried++
                Files.writeString(file, spoilt)
                try {
                    if (said(file)?.let { slip.seenIn(name, it) } == true) seen++ else println("  missed: ${slip.what} `$name` in ${repo.relativize(file)}")
                } finally {
                    Files.writeString(file, original)
                }
            }
            println("post-edit slips: ${slip.what}: seen $seen of $tried (${if (tried == 0) 0 else seen * 100 / tried} %)")
            assertTrue(tried >= 15, "${slip.what}: only $tried files to try it on")
            if (slip.what.startsWith("a missing")) assertTrue(seen * 100 >= tried * 98, "${slip.what}: $seen of $tried")
        }
    }
}
