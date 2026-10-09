package codeloupe.index

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.processes.SystemProcesses
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseWorkerTest {
    private val kotlinSource = "package demo\n\nimport java.util.UUID\n\nclass Billing(private val rate: Int) {\n    /** Total. */\n    fun total(a: Int, b: Int = 2): Int = helper(a) + rate\n    private fun helper(x: Int) = x * UUID.randomUUID().hashCode()\n}\n"
    private val javaSource = "package demo;\n\nclass Order {\n    int size() { return 3; }\n}\n"

    private fun waitUntil(seconds: Long = 20, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun `a worker returns the facts a parse in this process gives, for Kotlin and Java`() {
        ParseWorkerClient().use { client ->
            for ((path, text) in listOf("src/Billing.kt" to kotlinSource, "src/Order.java" to javaSource)) {
                assertEquals(Extraction.parseHere(path, text), client.extract(path, text), path)
            }
            assertNotNull(client.pid)
        }
    }

    @Test
    fun `a worker with nothing to parse ends by itself and the next file starts another`() {
        ParseWorkerClient(idleSeconds = 2).use { client ->
            assertNotNull(client.extract("src/Billing.kt", kotlinSource))
            val first = client.pid!!
            assertTrue(waitUntil { ProcessHandle.of(first).isEmpty }, "it ended after 2 idle seconds")
            assertNull(client.pid)
            assertNotNull(client.extract("src/Billing.kt", kotlinSource))
            assertNotEquals(first, client.pid)
        }
    }

    @Test
    fun `a worker that was killed is replaced and the file is parsed anyway`() {
        ParseWorkerClient().use { client ->
            client.extract("src/Billing.kt", kotlinSource)
            ProcessHandle.of(client.pid!!).get().destroyForcibly()
            assertEquals(Extraction.parseHere("src/Order.java", javaSource), client.extract("src/Order.java", javaSource))
        }
    }

    @Test
    fun `when no worker can be started the files are parsed in this process`() {
        val before = Extraction.parsed.get()
        ParseWorkerClient(command = { listOf("codeloupe-no-such-program") }).use { client ->
            assertNull(client.extract("src/Billing.kt", kotlinSource))
            Extraction.useWorker(client)
            try {
                assertEquals(Extraction.parseHere("src/Billing.kt", kotlinSource), Extraction.extract("src/Billing.kt", kotlinSource))
            } finally {
                Extraction.useThisProcess()
            }
        }
        assertEquals(before + 1, Extraction.parsed.get())
    }

    @Test
    fun `the daemon asks a worker to parse an edited file and it ends with the daemon`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf("src/main/kotlin/demo/Billing.kt" to "package demo\n\nclass Billing {\n    fun total(a: Int): Int = a\n}\n"))
        val worktree = repo.resolveSibling(repo.fileName.toString().lowercase() + "-worktrees").createDirectories().resolve("CL-1")
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "CL-1", worktree.toString(), "main")
        val port = ServerSocket(0).use { it.localPort }
        val daemon = Daemon.start(Config(TestRepos.tmpDir("pw-home"), port, 60_000, 120_000, 512, null, parseWorkerIdleSeconds = 60))
        val http = HttpClient.newHttpClient()
        fun find(root: String, q: String): String {
            val body = buildJsonObject { put("root", root); put("q", q) }.toString()
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/find")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token).POST(HttpRequest.BodyPublishers.ofString(body)).build()
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        }
        fun workers() = SystemProcesses().read().filter { it.commandLine?.contains("codeloupe.index.ParseWorker") == true }
        try {
            assertTrue(find(repo.toString(), "total").contains("Billing"))
            val before = workers().map { it.pid }.toSet()
            worktree.resolve("src/main/kotlin/demo/Billing.kt").writeText("package demo\n\nclass Billing {\n    fun total(a: Int): Int = a\n    fun brandNewFunction(): Int = 5\n}\n")
            assertTrue(waitUntil { find(worktree.toString(), "brandNewFunction").contains("brandNewFunction") }, "the edit is parsed and found")
            val started = workers().map { it.pid }.toSet() - before
            assertEquals(1, started.size, "one worker parsed it")
            daemon.stop()
            assertTrue(waitUntil { workers().none { it.pid in started } }, "the worker ended with the daemon")
        } finally {
            runCatching { daemon.stop() }
        }
    }
}
