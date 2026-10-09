package codeloupe.triage

import codeloupe.TestRepos
import codeloupe.compress.ErrorLines
import codeloupe.compress.JavacDiagnostic
import codeloupe.compress.OutputCompressor
import codeloupe.index.BaseBuilder
import codeloupe.query.View
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * javac prints its diagnostics in the language of the JVM it runs in. The outputs under `outputs/triage/java-errors-*-constructed.txt`
 * are the English run of `java-errors.txt` written out in German, Czech and Japanese from javac's message vocabulary (constructed,
 * not captured), with a warning among the errors: the summary and the triage must treat them like the English one.
 */
class LocalizedJavacTest {
    private val locales = listOf("de" to "Fehler", "cs" to "chyba", "ja" to "エラー")

    private fun output(name: String) = javaClass.getResource("/outputs/triage/$name.txt")!!.readText()

    private fun summary(name: String): String = OutputCompressor.compress(listOf("./gradlew", "build"), output(name), "/work/proj").text

    @Test
    fun `a localized error line is kept in the summary and its warning is not an error`() {
        for ((code, word) in locales) {
            val text = summary("java-errors-$code-constructed")
            assertContains(text, "Mailer.java:12: $word:", message = code)
            assertContains(text, "Mailer.java:17: $word:", message = code)
            assertContains(text, "Mailer.java:18: $word:", message = code)
            assertFalse("Mailer.java:10:" in text, "$code: a warning is no error line:\n$text")
        }
    }

    @Test
    fun `localized javac errors point to their method like the English ones`() {
        val repo = TestRepos.fixtureRepo("triage/java")
        val base = TestRepos.tmpDir("triage-localized").resolve("base.db")
        BaseBuilder.build(repo.toString(), TestRepos.git(repo, "rev-parse", "HEAD"), base)
        for ((code, _) in locales) {
            // ErrorTriage itself: Triage keeps the plain summary when the pointers would make it more than 15 % longer, as short Japanese messages do.
            val text = View(base).use { ErrorTriage.apply(summary("java-errors-$code-constructed").lines(), ViewLocator(it)).joinToString("\n") }
            assertContains(text, Regex("src/main/java/demo/Mailer\\.java:\\d+-\\d+ {2}\\[Mailer] .*send.* {2}· symbol Mailer\\.send hash=[0-9a-f]{10}\n {2}12 {2}"), code)
            assertContains(text, Regex("\\[Mailer] .*count.*· symbol Mailer\\.count hash=[0-9a-f]{10}\n {2}17 {2}"), code)
            assertFalse("[cast]" in text, "$code: the warning stays out:\n$text")
        }
    }

    @Test
    fun `the label of a diagnostic decides between error, warning and note in any language`() {
        fun severity(line: String) = JavacDiagnostic.parse(line)?.severity
        assertEquals(JavacDiagnostic.Severity.ERROR, severity("src/Foo.java:3: error: ';' expected"))
        assertEquals(JavacDiagnostic.Severity.ERROR, severity("src/Foo.java:3: Fehler: ';' erwartet"))
        assertEquals(JavacDiagnostic.Severity.ERROR, severity("src/Foo.java:3: エラー: ';'がありません"))
        assertEquals(JavacDiagnostic.Severity.ERROR, severity("src/Foo.java:3: 错误: 需要';'"))
        assertEquals(JavacDiagnostic.Severity.ERROR, severity("src/Foo.java:3: chyba: očekává se ';'"))
        assertEquals(JavacDiagnostic.Severity.WARNING, severity("src/Foo.java:3: warning: [deprecation] f() in A has been deprecated"))
        assertEquals(JavacDiagnostic.Severity.WARNING, severity("src/Foo.java:3: Warnung: [deprecation] f() in A ist veraltet"))
        assertEquals(JavacDiagnostic.Severity.WARNING, severity("src/Foo.java:3: 警告: [deprecation] A のf()は推奨されません"))
        assertEquals(JavacDiagnostic.Severity.WARNING, severity("src/Foo.java:3: varování: [deprecation] f() je zastaralé"))
        assertEquals(JavacDiagnostic.Severity.NOTE, severity("src/Foo.java:3: Hinweis: Eine Notiz"))
        assertEquals(JavacDiagnostic.Severity.ERROR, severity("src/Foo.java:3: erreur : ';' attendu"))
        assertNull(severity("src/Foo.java:3: TODO: later"))
        assertNull(severity("src/Foo.java: error: no line number"))
        assertNull(severity("at com.acme.Foo.run(Foo.java:3)"))
    }

    @Test
    fun `an error in a language the table does not know is told by the caret line under it`() {
        val lines = listOf("src/Foo.java:3: xyzzy: something is wrong", "        foo();", "        ^")
        assertNull(JavacDiagnostic.parse(lines[0]))
        assertEquals(JavacDiagnostic.Severity.ERROR, JavacDiagnostic.classify(lines, 0)?.severity)
        assertTrue(ErrorLines.isErrorAt(lines, 0))
        assertFalse(ErrorLines.isErrorAt(listOf("src/Foo.java:3: xyzzy: a log line", "next line"), 0))
    }

    @Test
    fun `BuildError parses the localized error and not the warning`() {
        val error = assertNotNull(BuildError.parse("/w/src/Foo.java:3: Fehler: Symbol nicht gefunden"))
        assertEquals(BuildError("/w/src/Foo.java", 3, null, "Symbol nicht gefunden", "/w/src/Foo.java:3: Fehler: Symbol nicht gefunden"), error)
        assertEquals("src/Foo.java", BuildError.parse("src/Foo.java:3: エラー: x")?.path)
        assertNull(BuildError.parse("/w/src/Foo.java:3: Warnung: [cast] redundanter Cast"))
        assertNull(BuildError.parse("/w/src/Foo.java:3: 警告: [cast]"))
    }

    @Test
    fun `a file URI on Unix keeps its root slash so the working directory still matches`() {
        assertEquals("src/Foo.kt", ErrorLines.relative("file:///home/u/repo/src/Foo.kt", "/home/u/repo"))
        assertEquals("src/Foo.kt", ErrorLines.relative("file:////home/u/repo/src/Foo.kt", "/home/u/repo"))
        assertEquals("e: src/Foo.kt:3:4 x", ErrorLines.relative("e: file:///home/u/repo/src/Foo.kt:3:4 x", "/home/u/repo/"))
        assertEquals("src/Foo.kt", ErrorLines.relative("file:///C:/work/repo/src/Foo.kt", "C:/work/repo"))
        assertEquals("/other/Foo.kt", ErrorLines.relative("file:///other/Foo.kt", "/home/u/repo"))
    }
}
