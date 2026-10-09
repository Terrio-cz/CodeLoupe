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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** [ChangesTest] with Java sources, and a Java change whose caller is Kotlin. */
class JavaChangesTest {
    private val repo = TestRepos.fixtureRepo(
        "java/sample",
        mapOf(BILLING to billing(), USE to USE_TEXT, TEST to TEST_TEXT, OLD to "package demo;\n\nclass Old {\n}\n", KOTLIN_USE to KOTLIN_USE_TEXT),
    )
    private val feature = TestRepos.tmpDir("wt").resolve("feature").also { git(repo, "worktree", "add", "-q", "-b", "feature", it.toString()) }
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val registry = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))

    @Test
    fun `changed declarations of a branch with callers and tests, Kotlin callers of Java code included`() {
        // Committed on the branch: total's body, tax's signature, legacy removed, discount added; keep untouched but CRLF.
        write(feature, BILLING, billing(total = "return a + 1", tax = "int tax(int a, int rate) { return a * rate / 100; }", legacy = false, extra = "\n    int discount() { return 5; }\n").replace("\n", "\r\n"))
        Files.delete(feature.resolve(OLD))
        commit(feature, "branch work")
        // Uncommitted: a new file.
        write(feature, NEW, "package demo;\n\nclass Fresh {\n    int hello() {\n        return new Billing().keep();\n    }\n}\n")

        val text = changes(callers = true)
        assertContains(text, Regex("^changes vs main \\(merge-base [0-9a-f]{7}\\): 3 source files, 7 declarations \\(\\+3 ~1 \\^1 -2\\)"))
        assertContains(text, "  [Billing]\n    ~ 4-6  int total(int a)")
        assertContains(text, "callers 1: Use.useAll")
        assertContains(text, "tests 1: BillingTest")
        assertContains(text, "    ^ 8  int tax(int a, int rate)\n      was: int tax(int a)")
        assertContains(text, Regex("    - \\d+  int legacy\\(\\)\n      still referenced by name 1: Use.useAll"))
        assertContains(text, "    + 12  int discount()")
        assertContains(text, "(new)")
        assertContains(text, "class Fresh  (with 1 member)")
        assertContains(text, "(deleted)")
        assertFalse("keep()" in text.substringBefore(NEW), "an unchanged member is not listed, line ends do not count")
    }

    @Test
    fun `a Java signature change lists the Kotlin call it may have broken`() {
        write(feature, BILLING, billing(tax = "int tax(int a, int rate) { return a * rate / 100; }"))
        val text = changes(callers = true)
        assertContains(text, "  ^ 8  [Billing] int tax(int a, int rate)\n      was: int tax(int a)")
        assertContains(text, "callers 2: Use.useAll, useKotlin (UseKotlin.kt)")
    }

    @Test
    fun `bodies adds a line diff`() {
        write(feature, BILLING, billing(total = "return a + 1"))
        val text = changes(bodies = true)
        assertContains(text, "    - return a;\n    + return a + 1;".replace("return", "        return"))
        assertEquals(0, Regex("callers").findAll(text).count(), "callers come on request")
        assertEquals(1, Regex("callers").findAll(changes(callers = true, bodies = true)).count())
    }

    private fun changes(bodies: Boolean = false, callers: Boolean = false): String = runBlocking {
        ChangesTool.answer(registry, feature.toString(), ToolArgs(buildJsonObject {
            put("bodies", kotlinx.serialization.json.JsonPrimitive(bodies))
            put("callers", kotlinx.serialization.json.JsonPrimitive(callers))
        }))
    }

    private fun write(root: Path, path: String, text: String) {
        root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun commit(root: Path, message: String) {
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message)
    }

    private companion object {
        const val BILLING = "src/main/java/demo/Billing.java"
        const val USE = "src/main/java/demo/Use.java"
        const val KOTLIN_USE = "src/main/kotlin/demo/UseKotlin.kt"
        const val TEST = "src/test/java/demo/BillingTest.java"
        const val OLD = "src/main/java/demo/Old.java"
        const val NEW = "src/main/java/demo/Fresh.java"
        const val USE_TEXT = "package demo;\n\nclass Use {\n    int useAll() {\n        return new Billing().total(1) + new Billing().tax(2) + new Billing().legacy();\n    }\n}\n"
        const val KOTLIN_USE_TEXT = "package demo\n\nfun useKotlin(): Int = Billing().tax(3)\n"
        const val TEST_TEXT = "package demo;\n\nclass BillingTest {\n    int totals() {\n        return new Billing().total(3);\n    }\n}\n"

        fun billing(total: String = "return a", tax: String = "int tax(int a) { return a / 10; }", legacy: Boolean = true, extra: String = ""): String =
            "package demo;\n\nclass Billing {\n    int total(int a) {\n        $total;\n    }\n\n" +
                "    $tax\n\n    int keep() { return 1; }\n" +
                (if (legacy) "\n    int legacy() { return 0; }\n" else "") + extra + "}\n"
    }
}
