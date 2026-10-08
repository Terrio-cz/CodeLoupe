package codeloupe.triage

import codeloupe.TestRepos
import codeloupe.compress.OutputCompressor
import codeloupe.index.BaseBuilder
import codeloupe.query.View
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compile and test failures of real Gradle runs (outputs under `outputs/triage`, paths scrubbed; the sources they were run on under
 * `fixtures/triage`): each error comes back with the declaration it is in.
 */
class TriageTest {
    private fun output(name: String) = javaClass.getResource("/outputs/triage/$name.txt")!!.readText()

    private fun summary(name: String): String =
        OutputCompressor.compress(listOf("./gradlew", "build"), output(name), "/work/proj").text

    private fun triaged(name: String, fixture: String): String {
        val repo = TestRepos.fixtureRepo("triage/$fixture")
        val base: Path = TestRepos.tmpDir("triage").resolve("base.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), base)
        return View(base).use { Triage.apply(summary(name), ViewLocator(it)) }
    }

    @Test
    fun `Kotlin errors are grouped by declaration and a repeated message is said once`() {
        val plain = summary("kotlin-errors")
        val text = triaged("kotlin-errors", "kotlin")
        assertContains(text, "same error ×3 in 3 declarations: Unresolved reference 'discountRate'.")
        assertContains(text, Regex("src/main/kotlin/demo/Billing\\.kt:\\d+-\\d+ {2}\\[Billing] fun total\\(…\\) {2}· symbol Billing\\.total hash=[0-9a-f]{10}"))
        assertContains(text, Regex("Billing\\.kt:\\d+-\\d+ {2}\\[Billing] fun label\\(…\\).*\n {2}15:26 {2}Initializer type mismatch"))
        assertContains(text, "taxRate")
        assertFalse("file:///" in text, "located errors lose the file URL")
        assertTrue(text.length <= plain.length * 1.15, "${text.length} vs ${plain.length}")
    }

    @Test
    fun `javac errors point to their method, several in one method share its line`() {
        val text = triaged("java-errors", "java")
        assertContains(text, Regex("src/main/java/demo/Mailer\\.java:\\d+-\\d+ {2}\\[Mailer] .*send.* {2}· symbol Mailer\\.send hash=[0-9a-f]{10}\n {2}12 {2}cannot find symbol"))
        assertContains(text, Regex("\\[Mailer] .*count.*· symbol Mailer\\.count hash=[0-9a-f]{10}\n {2}17 {2}incompatible types"))
        assertContains(text, "18")
    }

    @Test
    fun `a failed test shows its expected and actual value and where it failed`() {
        val text = triaged("test-failures", "test")
        val plain = summary("test-failures")
        assertTrue(text != plain && text.length <= plain.length * 1.15, "${text.length} vs ${plain.length}:\n$text")
        assertContains(text, "FAILED BillingTest > tax is a fifth()")
        assertContains(text, "  expected <40>, was <20>")
        assertContains(text, Regex("at BillingTest\\.kt:22 {2}· symbol BillingTest\\.kt:\\d+ hash=[0-9a-f]{10}"))
        assertContains(text, "expected <rate 10 total 180>, was <rate 10 total 190>")
    }

    @Test
    fun `lines the index cannot place stay exactly as the summary had them`() {
        val plain = summary("kotlin-errors")
        // The Java sources hold no file the Kotlin errors name.
        assertEquals(plain, triaged("kotlin-errors", "java"))
        assertEquals(summary("test-failures"), triaged("test-failures", "java"))
    }
}
