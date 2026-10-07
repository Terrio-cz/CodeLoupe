package codeloupe.changes

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import codeloupe.tools.ChangesTool
import codeloupe.tools.ToolArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `changes` on a fixture branch: committed and uncommitted work against the merge-base, not against the moved main. */
class ChangesTest {
    private val repo = TestRepos.fixtureRepo(
        "kotlin/sample",
        mapOf(BILLING to billing(), USE to USE_TEXT, TEST to TEST_TEXT, OLD to "package demo\n\nclass Old\n"),
    )
    private val feature = TestRepos.tmpDir("wt").resolve("feature").also { git(repo, "worktree", "add", "-q", "-b", "feature", it.toString()) }
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val registry = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))

    @Test
    fun `changed declarations of a branch with callers and tests`() {
        // Committed on the branch: total's body, tax's signature, legacy removed, discount added; keep untouched but CRLF.
        val changed = billing(total = "return a + 1", tax = "fun tax(a: Int, rate: Int): Int = a * rate / 100", legacy = false, extra = "\n    fun discount(): Int = 5\n")
        write(feature, BILLING, changed.replace("\n", "\r\n"))
        Files.delete(feature.resolve(OLD))
        commit(feature, "branch work")
        // Uncommitted: a new file.
        write(feature, NEW, "package demo\n\nclass Fresh {\n    fun hello() = Billing().keep()\n}\n")
        // The default branch moves on with a change the branch never saw.
        write(repo, "src/main/kotlin/demo/Extra.kt", "package demo\n\nclass Extra\n")
        commit(repo, "main moves")

        val text = changes()
        assertContains(text, Regex("^changes vs main \\(merge-base [0-9a-f]{7}\\): 3 source files, 7 declarations \\(\\+3 ~1 \\^1 -2\\)"))
        assertContains(text, "  ~ 4-6  [Billing] fun total(a: Int): Int")
        assertContains(text, "      callers 1: useAll (Use.kt)")
        assertContains(text, "      tests 1: BillingTest")
        assertContains(text, "  ^ 8-8  [Billing] fun tax(a: Int, rate: Int): Int\n      was: fun tax(a: Int): Int")
        assertContains(text, Regex("  - \\d+-\\d+  \\[Billing\\] fun legacy\\(\\): Int\n      still referenced by name 1: useAll \\(Use.kt\\)"))
        assertContains(text, "  + 12-12  [Billing] fun discount(): Int")
        assertContains(text, "$NEW  (new)\n  + 3-5  class Fresh  (with 1 member)\n")
        assertContains(text, "$OLD  (deleted)\n  - 3-3  class Old")
        assertFalse("keep()" in text.substringBefore(NEW), "an unchanged member is not listed, line ends do not count")
        assertFalse("Extra" in text, "main's own changes are not the branch's")
    }

    @Test
    fun `bodies adds a line diff, a clean branch says so`() {
        assertEquals("changes vs main (merge-base ${git(repo, "rev-parse", "HEAD").take(7)}): 0 source files, no declaration changed", changes())
        write(feature, BILLING, billing(total = "return a + 1"))
        val text = changes(bodies = true)
        assertContains(text, "    - return a\n    + return a + 1".replace("return", "        return"))
        assertTrue(text.lines().size < 12, text)
    }

    @Test
    fun `files without declaration changes and non-source files are named, not expanded`() {
        write(feature, USE, USE_TEXT.replace("package demo\n", "package demo\n\n// a comment\n"))
        write(feature, "docs/notes.md", "notes\n")
        val text = changes()
        assertContains(text, "no declaration changed (imports, comments, formatting): $USE")
        assertContains(text, "other changed files: docs/notes.md")
    }

    @Test
    fun `an added member does not change its class, a KDoc edit is labelled, limit bounds the listing`() {
        write(feature, BILLING, billing(extra = "\n    fun discount(): Int = 5\n").replace("    fun keep()", "    /** Kept. */\n    fun keep()"))
        for (i in 0 until 5) write(feature, "src/main/kotlin/demo/More$i.kt", "package demo\n\nfun more$i() = $i\n")
        val text = changes(limit = 3)
        assertFalse("class Billing" in text, text)
        assertContains(text, "fun keep(): Int  (KDoc only)")
        assertContains(text, "… +4 more declarations (raise limit)")
        assertEquals(1, text.lines().count { it.startsWith("src/main/kotlin/demo/More") }, "no file headings past the limit")
    }

    @Test
    fun `a signature change lists calls an overload now takes`() {
        write(repo, PAY, "package demo\n\nclass Pay {\n    fun pay(a: Int): Int = a\n\n    fun pay(a: String, b: Int = 0): Int = b\n}\n")
        write(repo, PAY_USE, "package demo\n\nfun payAll(): Int = Pay().pay(1) + Pay().pay(\"x\")\n")
        commit(repo, "pay")
        git(feature, "merge", "-q", "main")
        write(feature, PAY, "package demo\n\nclass Pay {\n    fun pay(a: Int, c: Int): Int = a + c\n\n    fun pay(a: String, b: Int = 0): Int = b\n}\n")
        val text = changes()
        assertContains(text, "  ^ 4-4  [Pay] fun pay(a: Int, c: Int): Int\n      was: fun pay(a: Int): Int")
        assertContains(text, "may be redirected (fit the old signature, now resolve to another overload) 2: payAll (PayUse.kt) ×2")
    }

    @Test
    fun `two worktrees on one merge-base at once each get their own old versions`() {
        val other = TestRepos.tmpDir("wt").resolve("other").also { git(repo, "worktree", "add", "-q", "-b", "other", it.toString()) }
        write(feature, BILLING, billing(total = "return a + 1"))
        write(other, USE, USE_TEXT.replace("total(1)", "total(2)"))
        val (a, b) = runBlocking {
            val args = ToolArgs(buildJsonObject { })
            val first = async(Dispatchers.Default) { ChangesTool.answer(registry, feature.toString(), args) }
            val second = async(Dispatchers.Default) { ChangesTool.answer(registry, other.toString(), args) }
            first.await() to second.await()
        }
        assertContains(a, "  ~ 4-6  [Billing] fun total(a: Int): Int")
        assertContains(b, "  ~ 3-3  fun useAll(): Int")
    }

    @Test
    fun `a file removed from the index but still on disk is changed, not deleted`() {
        git(feature, "rm", "-q", "--cached", USE)
        write(feature, USE, USE_TEXT.replace("total(1)", "total(5)"))
        val text = changes()
        assertContains(text, "$USE\n  ~ 3-3  fun useAll(): Int")
    }

    private fun changes(bodies: Boolean = false, limit: Int = 60): String = runBlocking {
        val args = buildJsonObject {
            put("bodies", JsonPrimitive(bodies))
            put("limit", JsonPrimitive(limit))
        }
        ChangesTool.answer(registry, feature.toString(), ToolArgs(args))
    }

    private fun write(root: Path, path: String, text: String) {
        root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun commit(root: Path, message: String) {
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message)
    }

    private companion object {
        const val BILLING = "src/main/kotlin/demo/Billing.kt"
        const val USE = "src/main/kotlin/demo/Use.kt"
        const val TEST = "src/test/kotlin/demo/BillingTest.kt"
        const val OLD = "src/main/kotlin/demo/Old.kt"
        const val NEW = "src/main/kotlin/demo/Fresh.kt"
        const val PAY = "src/main/kotlin/demo/Pay.kt"
        const val PAY_USE = "src/main/kotlin/demo/PayUse.kt"
        const val USE_TEXT = "package demo\n\nfun useAll(): Int = Billing().total(1) + Billing().tax(2) + Billing().legacy()\n"
        const val TEST_TEXT = "package demo\n\nclass BillingTest {\n    fun totals() = Billing().total(3)\n}\n"

        fun billing(
            total: String = "return a",
            tax: String = "fun tax(a: Int): Int = a / 10",
            legacy: Boolean = true,
            extra: String = "",
        ) = "package demo\n\nclass Billing {\n    fun total(a: Int): Int {\n        $total\n    }\n\n    $tax\n\n" +
            (if (legacy) "    fun legacy(): Int = 0\n\n" else "") + "    fun keep(): Int = 1\n$extra}\n"
    }
}
