package codeloupe.doc

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import codeloupe.tools.DocTool
import codeloupe.tools.ToolArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shared document layer behind `doc` (plans, brain notes, persisted tool outputs): digest, sections, windows, delta. */
class DocTest {
    private val dir = TestRepos.tmpDir("doc")
    private val outside = TestRepos.tmpDir("doc-outside")
    private var now = 1_791_400_000_000L
    private val memory = DocMemory()
    private val reader = DocReader(memory) { now }
    private val tool = DocTool(memory, DocFiles(emptyList()))

    private fun plan(extra: String = "") = buildString {
        append("# Plan CL-93\n\nOne sentence of intro.\n\n")
        append("## Goal\n\nPlanners read one pack.\n\n")
        append("## Steps\n\n1. Build the layer\n2. Build the tool\n\n### Risks\n\nA table that grows.\n\n")
        append("## Verification\n\n```\n# not a heading\n```\n\nRun the tests.\n$extra")
    }

    private fun doc(text: String, id: String = "plan.md") = Doc.parse(id, text)

    private fun read(doc: Doc, view: DocReader.View = DocReader.View.DIGEST, names: List<String> = emptyList(), session: String = "w1", forget: Boolean = false) =
        reader.read(session, doc, view, names, forget)

    private fun call(path: String, root: String = dir.toString(), vararg more: Pair<String, JsonElement>): String = runBlocking {
        tool.answer(registry, root, ToolArgs(buildJsonObject { put("path", JsonPrimitive(path)); more.forEach { (k, v) -> put(k, v) } }))
    }

    /** `doc` reads files and needs no repository; the registry is only the tool interface's argument. */
    private val registry = Registry(Config(TestRepos.tmpDir("doc-home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0), JobQueue(CoroutineScope(Dispatchers.Default)))

    @Test
    fun `markdown headings make sections with unique handles, fences are not headings`() {
        val d = doc(plan())
        assertEquals(listOf("plan-cl-93", "goal", "steps", "risks", "verification"), d.sections.map { it.handle })
        assertContains(d.find("steps").single().body, "### Risks", message = "a section's body runs through its sub-sections")
        assertFalse("Risks" in d.find("steps").single().own, "its own text stops at the next heading")
        assertEquals(listOf("verification"), d.find("Verif").map { it.handle }, "a heading prefix finds it, case-insensitively")
        assertEquals(listOf("a", "a-2"), doc("## A\nx\n## A\ny\n").sections.map { it.handle })
    }

    @Test
    fun `a digest of a large document names its sections by handle within 1000 characters`() {
        val big = (1..80).joinToString("\n") { "## Section number $it\n" + "text line\n".repeat(it % 7 + 1) }
        val digest = read(doc(big))
        assertTrue(digest.length <= DocDigest.LIMIT, "${digest.length} chars")
        assertContains(digest, "plan.md · ")
        assertContains(digest, "section-number-1(")
        assertContains(digest, "fetch: section=<handle>")
        assertContains(read(doc(big), DocReader.View.OUTLINE, session = "w2"), "section-number-80(")
    }

    @Test
    fun `a section is fetched by handle or heading, not the rest, and unchanged text is not sent twice`() {
        val d = doc(plan())
        val steps = read(d, names = listOf("Steps"))
        assertContains(steps, "2. Build the tool")
        assertContains(steps, "A table that grows.")
        assertFalse("Planners read one pack." in steps)
        val again = read(d, names = listOf("steps"))
        assertTrue(again.startsWith("section steps unchanged since your read"), again)
        assertTrue(again.length < 100, again)
        assertContains(read(d, names = listOf("steps"), forget = true), "Build the layer")
        assertContains(read(d, names = listOf("nope")), "no section 'nope'; have: plan-cl-93, goal, steps")
    }

    @Test
    fun `a repeated read of an unchanged plan is one short line, per session`() {
        val d = doc(plan())
        read(d, DocReader.View.FULL)
        val again = read(d, DocReader.View.FULL)
        assertTrue(again.startsWith("plan.md unchanged since your read at "), again)
        assertTrue(again.length < 150, "${again.length} chars")
        assertContains(read(d, DocReader.View.FULL, session = "w2"), "Build the layer")
        assertContains(read(d, DocReader.View.FULL, forget = true), "Build the layer")
        read(d)
        assertTrue(read(d).startsWith("plan.md unchanged since"), "the digest is remembered too")
        read(d, session = "")
        assertFalse(read(d, session = "").contains("unchanged"), "no session key, no memory")
    }

    @Test
    fun `after an edit the reader gets only what changed`() {
        read(doc(plan()), DocReader.View.FULL)
        read(doc(plan()))
        val edited = doc(plan().replace("Run the tests.", "Run the tests twice.") + "\n## Notes\n\nNew.\n")
        val delta = read(edited)
        assertTrue(delta.startsWith("plan.md changed since your read at "), delta)
        assertContains(delta, "~ verification(")
        assertContains(delta, "+ notes(")
        assertFalse("goal" in delta, "an unchanged section is not listed: $delta")
        val full = read(edited, DocReader.View.FULL)
        assertContains(full, "changed sections in full, unchanged omitted")
        assertContains(full, "Run the tests twice.")
        assertFalse("Planners read one pack." in full)
        assertTrue(read(edited, DocReader.View.FULL).startsWith("plan.md unchanged"))
    }

    @Test
    fun `an unstructured output is cut into line windows and its errors are indexed with handles`() {
        val log = (1..200).joinToString("\n") { n ->
            when (n) {
                120 -> "FAILED: BillingTest > total() at BillingTest.kt:12"
                121 -> "java.lang.AssertionError: expected 3 but was 4"
                else -> "line $n ok"
            }
        }
        val d = doc(log, "build.log")
        assertEquals(listOf("L1-60", "L61-120", "L121-180", "L181-200"), d.sections.map { it.handle })
        val digest = read(d)
        assertTrue(digest.length <= DocDigest.LIMIT)
        assertContains(digest, "errors 1: L118-124 \"FAILED: BillingTest")
        val window = read(d, names = listOf("L118-124"))
        assertContains(window, "build.log L118-124 of 200 lines:")
        assertContains(window, "java.lang.AssertionError: expected 3 but was 4")
        assertContains(window, "line 118 ok")
        assertFalse("line 126 ok" in window)
    }

    @Test
    fun `a markdown document has no error index, an output with banners does`() {
        assertTrue(doc(plan("\nThis step may fail with an error.\n")).errors.isEmpty())
        val banners = "=== build ===\nok\n=== test ===\nFAILED: BillingTest\nat line\n"
        assertEquals(1, doc(banners, "ci.log").errors.size)
        assertEquals(listOf("build", "test"), doc(banners, "ci.log").sections.map { it.handle })
    }

    @Test
    fun `the doc tool reads a file under root and answers unchanged on the second read`() {
        dir.resolve("brain").createDirectories()
        dir.resolve("brain/TER-5.md").writeText(plan())
        val first = call("brain/TER-5.md")
        assertContains(first, "brain/TER-5.md · ")
        assertContains(first, "goal(")
        val second = call("brain/TER-5.md")
        assertTrue(second.startsWith("brain/TER-5.md unchanged since your read at "), second)
        assertContains(call("brain/TER-5.md", dir.toString(), "section" to JsonArray(listOf(JsonPrimitive("goal")))), "Planners read one pack.")
        dir.resolve("brain/TER-5.md").writeText(plan("\n## Later\n\nMore.\n"))
        assertContains(call("brain/TER-5.md"), "+ later(")
    }

    @Test
    fun `only files under root or an allowed folder are read, and never a secret store`() {
        outside.resolve("note.md").writeText("# A\n\nx\n")
        dir.resolve(".env").writeText("KEY=1\n")
        dir.resolve("blob.bin").let { java.nio.file.Files.write(it, byteArrayOf(1, 0, 2)) }
        assertContains(assertFailsWith<IllegalArgumentException> { call(outside.resolve("note.md").toString()) }.message!!, "outside root")
        assertContains(assertFailsWith<IllegalArgumentException> { call(".env") }.message!!, "secret store")
        assertContains(assertFailsWith<IllegalArgumentException> { call("blob.bin") }.message!!, "binary")
        assertContains(assertFailsWith<IllegalArgumentException> { call("missing.md") }.message!!, "no file")
        assertContains(assertFailsWith<IllegalArgumentException> { call("../${dir.fileName}/../${outside.fileName}/note.md") }.message!!, "outside root")
        val allowed = DocTool(DocMemory(), DocFiles(listOf(outside)))
        val answer = runBlocking { allowed.answer(registry, dir.toString(), ToolArgs(buildJsonObject { put("path", JsonPrimitive(outside.resolve("note.md").toString())) })) }
        assertContains(answer, "note.md")
    }
}
