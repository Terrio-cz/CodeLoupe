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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The post-edit check of a real daemon over a real repository: what it says about a file the agent just wrote, and when it stays silent. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostEditDaemonTest {
    private val port = ServerSocket(0).use { it.localPort }
    private val config = Config(TestRepos.tmpDir("post-edit-home"), port, queryTimeoutMs = 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null)
    private val repo = TestRepos.fixtureRepo("kotlin/sample", SOURCES)
    private val daemon = Daemon.start(config)
    private val http = HttpClient.newHttpClient()
    private var calls = 0

    init {
        val find = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/find")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, daemon.token)
            .POST(HttpRequest.BodyPublishers.ofString("""{"root":"${repo.toString().replace("\\", "/")}","q":"Orders"}""")).build()
        assertContains(http.send(find, HttpResponse.BodyHandlers.ofString()).body(), "class Orders")
        enable(true)
    }

    @AfterAll
    fun stop() = daemon.stop()

    private fun enable(on: Boolean) = Files.writeString(config.home.resolve("config.json"), """{"hooks":{"postEdit":{"enabled":$on,"timeoutMs":2000}}}""")

    private fun edit(file: String, text: String, tool: String = "Edit"): String? {
        val path = repo.resolve(file).also { Files.createDirectories(it.parent) }
        Files.writeString(path, text)
        return post(path, tool)
    }

    /** The additional context the daemon answers for [path] having just been written, or null for "nothing to add". */
    private fun post(path: Path, tool: String = "Edit"): String? {
        val body = buildJsonObject {
            put("hook_event_name", "PostToolUse")
            put("session_id", "s-${calls++}")
            put("cwd", repo.toString())
            put("tool_name", tool)
            put("tool_input", buildJsonObject { put("file_path", path.toString()) })
        }.toString()
        val response = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/hook")).header("content-type", "application/json").header(CodeLoupe.HEADER, "1")
                .header(CodeLoupe.TOKEN_HEADER, daemon.token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() == 204) return null
        val output = Json.parseToJsonElement(response.body()).jsonObject["hookSpecificOutput"]!!.jsonObject
        assertEquals("PostToolUse", output["hookEventName"]!!.jsonPrimitive.content)
        return output["additionalContext"]!!.jsonPrimitive.content
    }

    @Test
    fun `a syntax error is told with its place and a file that parses is not judged on names`() {
        val said = edit(ORDERS_FILE, ORDERS.replace("fun total(a: Int, b: Int = 0): Int {", "fun total(a: Int, b: Int = 0: Int {").replace("fun tax", "fun tax(x: Int) = bogusName(x)\n    fun tax2"))!!
        assertContains(said, "Orders.kt")
        assertContains(said, "syntax error")
        assertContains(said, "not a compiler")
        assertFalse("bogusName" in said)
        assertTrue(said.lines().size <= 5, said)
    }

    @Test
    fun `a Java file with a syntax error is told too`() {
        val said = edit("src/main/java/com/example/shop/Legacy.java", "package com.example.shop;\npublic class Legacy {\n  int f( { return 1; }\n}\n")!!
        assertContains(said, "Legacy.java")
        assertContains(said, "3:")
    }

    @Test
    fun `a function nothing declares is unknown, a library or imported name is not`() {
        val said = edit(
            "src/main/kotlin/com/example/shop/Report.kt",
            """
            package com.example.shop

            import kotlin.math.abs

            class Report {
                fun lines(): List<Int> = listOf(abs(-1), reportTotal(2), Orders().total(1))
                fun later(): Int = sortedMapOf(1 to 2).size + buildAuditTrail(3)
            }
            """.trimIndent() + "\n",
        )!!
        assertContains(said, "unknown function `buildAuditTrail`")
        assertContains(said, "unknown function `reportTotal`")
        assertFalse("`abs`" in said || "`listOf`" in said || "`sortedMapOf`" in said, said)
    }

    @Test
    fun `a name another package declares is told as a missing import, a name nobody declares as unknown`() {
        val said = edit(
            "src/main/kotlin/com/example/other/Use.kt",
            "package com.example.other\n\nimport com.example.shop.Orders\n\nclass Use {\n    fun go(): Int = Orders().total(1) + discount(2) + nowhere(3) + Plain.hashCode()\n}\n",
        )!!
        assertContains(said, "`discount` is not imported: it is declared as com.example.shop.discount (Orders.kt:")
        assertContains(said, "unknown function `nowhere`")
        assertFalse("`Orders`" in said || "`Plain`" in said, said)
    }

    @Test
    fun `a call with the wrong number of arguments names the function and what it takes`() {
        val said = edit(
            "src/main/kotlin/com/example/shop/Calls.kt",
            "package com.example.shop\n\nclass Calls {\n    fun ok() = Orders().total(1) + Orders().total(1, 2) + discount(5)\n    fun bad() = Orders().total(1, 2, 3) + discount()\n}\n",
        )!!
        assertContains(said, "`total` called with 3 argument(s), but it takes 1 to 2")
        assertContains(said, "`discount` called with 0 argument(s), but it takes 1")
        assertContains(said, "Orders.kt:")
        assertFalse("called with 1 argument" in said || "called with 2 argument" in said, said)
    }

    @Test
    fun `a clean edit, the same findings again, other files and a file outside the index say nothing`() {
        assertEquals(null, edit(ORDERS_FILE, ORDERS))
        val broken = ORDERS.replace("fun total(a: Int, b: Int = 0): Int {", "fun total(a: Int, b: Int = 0: Int {")
        assertTrue(edit(ORDERS_FILE, broken, tool = "Write") != null)
        val path = repo.resolve(ORDERS_FILE)
        Files.writeString(path, ORDERS)
        assertEquals(null, edit("README.md", "# not code\n"))
        assertEquals(null, post(repo.resolve("missing/Nothing.kt")))
        val outside = TestRepos.tmpDir("outside").resolve("Loose.kt").also { Files.writeString(it, "fun broken( {") }
        assertEquals(null, post(outside))
    }

    @Test
    fun `it is off until configured and tools other than the edit tools are left alone`() {
        val path = repo.resolve("src/main/kotlin/com/example/shop/Off.kt").also { Files.writeString(it, "class Off(") }
        assertTrue(post(path) != null)
        assertEquals(null, post(path, tool = "Read"))
        enable(false)
        try {
            assertEquals(null, post(path))
        } finally {
            enable(true)
        }
        Files.writeString(config.home.resolve("config.json"), "{}")
        try {
            assertEquals(null, post(path), "off by default")
        } finally {
            enable(true)
        }
        assertTrue((daemon.status().hooks.passed["off"] ?: 0) >= 2)
    }

    private companion object {
        const val ORDERS_FILE = "src/main/kotlin/com/example/shop/Orders.kt"

        val ORDERS = """
            package com.example.shop

            class Orders {
                fun total(a: Int, b: Int = 0): Int {
                    return a + b
                }

                fun tax(x: Int): Int = x
            }

            fun discount(percent: Int): Int = percent
        """.trimIndent() + "\n"

        val SOURCES = mapOf(
            ORDERS_FILE to ORDERS,
            "src/main/kotlin/com/example/other/Plain.kt" to "package com.example.other\n\nclass Plain\n",
        )
    }
}
