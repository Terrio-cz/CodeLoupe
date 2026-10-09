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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `changes tests=true`: the Gradle command for the tests that use what a branch changed, and the honest widenings. */
class TestSelectionTest {
    private val repo = TestRepos.fixtureRepo(
        "kotlin/sample",
        mapOf(
            "core/src/main/kotlin/demo/Billing.kt" to BILLING,
            "core/src/main/kotlin/demo/Report.kt" to "package demo\n\nclass Report {\n    fun render(): String = Billing().total(1).toString()\n}\n",
            "core/src/test/kotlin/demo/BillingTest.kt" to "package demo\n\nclass BillingTest {\n    @Test fun totals() = Billing().total(3) + Billing().doubled(1)\n}\n",
            "core/src/test/kotlin/demo/ReportTest.kt" to "package demo\n\nclass ReportTest {\n    class Nested {\n        @Test fun renders() = Report().render()\n    }\n}\n",
            "web/src/main/kotlin/web/Shipping.kt" to "package web\n\nclass Shipping {\n    fun label(): String = \"x\"\n}\n",
            "web/src/test/kotlin/web/OtherTest.kt" to "package web\n\nclass OtherTest {\n    @Test fun unrelated() = 1\n}\n",
            "mail/src/main/java/mail/Mailer.java" to "package mail;\n\npublic class Mailer {\n    public String send(String to) { return to; }\n}\n",
            "mail/src/test/java/mail/MailerTest.java" to "package mail;\n\npublic class MailerTest {\n    @Test public void sends() { new Mailer().send(\"a\"); }\n}\n",
        ),
    )
    private val feature = TestRepos.tmpDir("wt").resolve("feature").also { git(repo, "worktree", "add", "-q", "-b", "feature", it.toString()) }
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val registry = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))

    @Test
    fun `a changed function selects the tests that use it, directly or through its callers`() {
        write("core/src/main/kotlin/demo/Billing.kt", BILLING.replace("return a", "return a + 1"))
        val text = tests()
        assertContains(text, "./gradlew :core:test --tests 'demo.BillingTest' --tests 'demo.ReportTest*'")
        assertContains(text, "  BillingTest <- Billing.total")
        // Report.render calls total; its test is nested in ReportTest, so the filter takes the nested classes too.
        assertContains(text, "  ReportTest <- Billing.total")
        assertFalse("whole module" in text, text)
    }

    @Test
    fun `a private helper is reached through the public function that calls it`() {
        write("core/src/main/kotlin/demo/Billing.kt", BILLING.replace("a * 2", "a * 3"))
        val text = tests()
        assertContains(text, "./gradlew :core:test --tests 'demo.BillingTest'\n")
        assertContains(text, "BillingTest <- Billing.double")
    }

    @Test
    fun `a declaration no test reaches runs its whole module and says why`() {
        write("web/src/main/kotlin/web/Shipping.kt", "package web\n\nclass Shipping {\n    fun label(): String = \"y\"\n}\n")
        val text = tests()
        assertContains(text, "./gradlew :web:test")
        assertFalse("--tests" in text.lines().first { it.startsWith("./gradlew") }, text)
        assertContains(text, ":web:test whole module: Shipping.label (no test uses it)")
    }

    @Test
    fun `a test helper is followed to the tests that use it, a class without test methods is never named`() {
        write("core/src/test/kotlin/demo/Fixtures.kt", """
            package demo

            class Fixtures {
                fun bill() = Billing().total(9)
            }
        """.trimIndent() + "\n")
        write("core/src/test/kotlin/demo/UsesFixturesTest.kt", """
            package demo

            class UsesFixturesTest {
                @Test
                fun uses() = Fixtures().bill()
            }
        """.trimIndent() + "\n")
        commitAll()
        write("core/src/main/kotlin/demo/Billing.kt", BILLING.replace("return a", "return a + 2"))
        val line = tests().lines().first { it.startsWith("./gradlew") }
        assertContains(line, "--tests 'demo.UsesFixturesTest'")
        assertFalse("demo.Fixtures'" in line, line)
    }

    @Test
    fun `hashCode means the tests of its type`() {
        write("core/src/main/kotlin/demo/Billing.kt", BILLING.replace("class Billing {", "class Billing {\n    override fun hashCode(): Int = 7\n"))
        val line = tests().lines().first { it.startsWith("./gradlew") }
        assertContains(line, "--tests 'demo.BillingTest'")
    }

    @Test
    fun `a removed member that nothing uses means the tests of the type it belonged to`() {
        // The default branch still has the member; the worktree removes it.
        write(repo, "core/src/main/kotlin/demo/Billing.kt", BILLING.replace("class Billing {", "class Billing {\n    fun unused(): Int = 1\n"))
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "a member nothing uses")
        val removing = TestRepos.tmpDir("wt").resolve("removing").also { git(repo, "worktree", "add", "-q", "-b", "removing", it.toString(), "main") }
        write(removing, "core/src/main/kotlin/demo/Billing.kt", BILLING)
        val text = tests(removing)
        assertContains(text, "./gradlew :core:test --tests 'demo.BillingTest'")
        assertContains(text, "BillingTest <- Billing.unused")
        assertFalse("whole module" in text, text)
    }

    @Test
    fun `Java declarations and tests work too`() {
        write("mail/src/main/java/mail/Mailer.java", "package mail;\n\npublic class Mailer {\n    public String send(String to) { return to + \"!\"; }\n}\n")
        assertContains(tests(), "./gradlew :mail:test --tests 'mail.MailerTest'")
    }

    @Test
    fun `an edited test selects its own class`() {
        write("web/src/test/kotlin/web/OtherTest.kt", "package web\n\nclass OtherTest {\n    @Test fun unrelated() = 2\n}\n")
        assertContains(tests(), "./gradlew :web:test --tests 'web.OtherTest'")
    }

    @Test
    fun `a build file selects the full suite, a resource its module`() {
        write("core/build.gradle.kts", "plugins { kotlin(\"jvm\") }\n")
        val full = tests()
        assertContains(full, "full suite: ./gradlew test")
        assertContains(full, "core/build.gradle.kts changed")
        assertFalse("--tests" in full)

        val clean = TestRepos.tmpDir("wt").resolve("res").also { git(repo, "worktree", "add", "-q", "-b", "res", it.toString()) }
        write(clean, "web/src/main/resources/messages.properties", "a=b\n")
        write(clean, "docs/readme.md", "x\n")
        val text = tests(clean)
        assertContains(text, "./gradlew :web:test")
        assertContains(text, ":web:test whole module: web/src/main/resources/messages.properties (not code)")
        assertFalse("readme" in text)
    }

    @Test
    fun `nothing changed, nothing to run`() {
        assertContains(tests(), "no test needs to run")
    }

    @Test
    fun `twenty changed declarations stay within 40 lines`() {
        val fns = (0 until 20).joinToString("\n") { "    fun f$it(): Int = $it" }
        write("core/src/main/kotlin/demo/Many.kt", "package demo\n\nclass Many {\n$fns\n}\n")
        write("core/src/test/kotlin/demo/ManyTest.kt", "package demo\n\nclass ManyTest {\n    @Test fun all() = Many()\n${(0 until 20).joinToString("\n") { "    @Test fun t$it() = Many().f$it()" }}\n}\n")
        val text = tests()
        assertTrue(text.lines().size <= 40, text)
        assertContains(text, "--tests 'demo.ManyTest'")
    }

    private fun tests(root: Path = feature): String = runBlocking {
        ChangesTool.answer(registry, root.toString(), ToolArgs(buildJsonObject { put("tests", JsonPrimitive(true)) }))
    }

    private fun commitAll() {
        git(feature, "add", "-A")
        git(feature, "commit", "-q", "-m", "fixtures")
    }

    private fun write(path: String, text: String) = write(feature, path, text)

    private fun write(root: Path, path: String, text: String) {
        root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private companion object {
        const val BILLING = "package demo\n\nclass Billing {\n    fun total(a: Int): Int {\n        return a\n    }\n\n" +
            "    fun doubled(a: Int): Int = double(a)\n\n    private fun double(a: Int): Int = a * 2\n}\n"
    }
}
