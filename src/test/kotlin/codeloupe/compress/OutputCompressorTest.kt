package codeloupe.compress

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The compressors over recorded outputs (`src/test/resources/outputs`): how much they save and what they never drop. */
class OutputCompressorTest {
    private val cwd = "/home/dev/IdeaProjects/codeloupe-worktrees/CL-92"

    private fun recorded(name: String) = javaClass.getResource("/outputs/$name")!!.readText()

    private fun compress(name: String, vararg command: String) = OutputCompressor.compress(command.toList(), recorded(name), cwd)

    private fun saved(name: String, vararg command: String): Int = 100 - compress(name, *command).text.length * 100 / recorded(name).length

    @Test
    fun `recorded outputs shrink by the promised share and by family`() {
        assertTrue(saved("git-status.txt", "git", "status") >= 60)
        assertTrue(saved("git-log.txt", "git", "log", "-30") >= 60)
        assertTrue(saved("git-diff-stat.txt", "git", "diff", "--stat", "HEAD~40") >= 90)
        assertTrue(saved("git-diff-patch.txt", "git", "diff") >= 90)
        assertTrue(saved("gradle-build.txt", "./gradlew", "build") >= 90)
        assertTrue(saved("gradle-test-fail.txt", "./gradlew", "test") >= 75)
        assertTrue(saved("gradle-compile-error.txt", "./gradlew", "compileTestKotlin") >= 65)
        val all = listOf(
            "git-status.txt" to listOf("git", "status"), "git-log.txt" to listOf("git", "log"), "git-diff-stat.txt" to listOf("git", "diff", "--stat"),
            "gradle-build.txt" to listOf("gradlew", "build"), "gradle-test-fail.txt" to listOf("gradlew", "test"), "gradle-compile-error.txt" to listOf("gradlew", "compileTestKotlin"),
        )
        val before = all.sumOf { recorded(it.first).length }
        val after = all.sumOf { OutputCompressor.compress(it.second, recorded(it.first), cwd).text.length }
        assertTrue(after * 100 / before <= 15, "$before -> $after")
    }

    @Test
    fun `git status keeps the branch and a count with names per kind`() {
        val text = compress("git-status.txt", "git", "status").text
        assertEquals("main", text.lines().first())
        assertContains(text, "staged 2: deleted src/m1/F10.kt, modified src/m2/F11.kt")
        assertContains(text, "unstaged 9 modified: src/m0/F3.kt, src/m0/F6.kt")
        assertContains(text, "untracked 26: new1.txt")
        assertEquals(text.lines().size, compress("git-status-short.txt", "git", "status", "-sb").text.lines().size, "long and short formats say the same")
        val clean = OutputCompressor.compress(listOf("git", "status"), "On branch main\nYour branch is up to date with 'origin/main'.\n\nnothing to commit, working tree clean\n" + " ".repeat(600) + "\n")
        assertContains(clean.text, "clean")
    }

    @Test
    fun `git log is one line per commit and says the shared author and day once`() {
        val text = compress("git-log.txt", "git", "log", "-30").text
        assertEquals("Dev 2026-10-08:", text.lines().first())
        assertEquals(30, text.lines().size - 1)
        assertContains(text, "3194702 CL-93 keep the pack's declarations")
        assertFalse("Author:" in text || "Date:" in text)
    }

    @Test
    fun `git diff stat keeps the totals and the biggest files, a patch one line per file`() {
        val stat = compress("git-diff-stat.txt", "git", "diff", "--stat").text
        assertContains(stat, "384 files changed, 21412 insertions(+), 564 deletions(-)")
        assertContains(stat, "5301  app/package-lock.json")
        assertContains(stat, "… +364 more files")
        val patch = compress("git-diff-patch.txt", "git", "diff").text
        assertContains(patch, "11 files, +292 -12 (patch body in the full output)")
        assertContains(patch, "new src/main/kotlin/codeloupe/tools/DocTool.kt +36 -0")
        assertContains(patch, "src/main/kotlin/codeloupe/tools/Tools.kt +8 -3 (2 hunks)")
    }

    @Test
    fun `gradle keeps every failed test and compiler error and drops the chatter`() {
        val build = compress("gradle-build.txt", "./gradlew", "build").text
        assertTrue(build.startsWith("BUILD SUCCESSFUL in "), build)
        assertFalse("> Task" in build || "WARNING" in build)
        val tests = compress("gradle-test-fail.txt", "./gradlew", "test").text
        assertContains(tests, "3 tests completed, 2 failed")
        assertContains(tests, "FAILED TmpFailTest > names match()")
        assertContains(tests, "FAILED TmpFailTest > totals add up()")
        assertContains(tests, "expected: <alpha> but was: <beta>")
        assertContains(tests, "at app//codeloupe.TmpFailTest.names match(TmpFailTest.kt:14)")
        assertFalse("org.junit.jupiter.api.Assertions" in tests, "framework frames go")
        val compile = compress("gradle-compile-error.txt", "./gradlew", "compileTestKotlin").text
        assertContains(compile, "compiler errors 2:")
        assertContains(compile, "e: src/test/kotlin/codeloupe/TmpFailTest.kt:4:25 Unresolved reference 'unknownName'.")
        assertContains(compile, "e: src/test/kotlin/codeloupe/TmpFailTest.kt:5:27 Unresolved reference 'missing'.")
        assertFalse("/home/dev" in compile, "the working directory is cut off")
    }

    @Test
    fun `every error line of a recorded gradle output survives`() {
        for ((name, command) in listOf("gradle-compile-error.txt" to "compileTestKotlin", "gradle-test-fail.txt" to "test")) {
            val out = compress(name, "./gradlew", command).text
            recorded(name).lines().filter { it.startsWith("e: ") }.forEach { error ->
                assertContains(out, error.substringAfterLast(".kt:"), message = "dropped: $error")
            }
            recorded(name).lines().filter { it.endsWith(" FAILED") && !it.startsWith("> Task") }.forEach { failed ->
                assertContains(out, failed.removeSuffix(" FAILED"), message = "dropped: $failed")
            }
        }
    }

    @Test
    fun `a short output is returned as it is, an unknown long one keeps its head, its errors and its tail`() {
        assertEquals("a\nb", OutputCompressor.compress(listOf("echo"), "a\nb\n").text)
        val long = (1..300).joinToString("\n") { n -> if (n == 150) "error: disk full at step 150" else "progress step $n ok" }
        val result = OutputCompressor.compress(listOf("./build.sh"), long)
        assertEquals("generic", result.family)
        assertContains(result.text, "progress step 1 ok")
        assertContains(result.text, "error: disk full at step 150")
        assertContains(result.text, "progress step 300 ok")
        assertContains(result.text, "… 284 lines omitted; error lines kept:")
        val many = (1..60).joinToString("\n") { "error: problem number $it" } + "\n" + "fine\n".repeat(100)
        assertContains(OutputCompressor.compress(listOf("./check.sh"), many).text, "more error lines in the full output")
    }

    @Test
    fun `a command is recognised by its program or a shell script, not by a path in its arguments`() {
        val status = recorded("git-status.txt")
        assertEquals("gitstatus", OutputCompressor.compress(listOf("bash", "-c", "git status"), status, cwd).family)
        assertEquals("gitstatus", OutputCompressor.compress(listOf("C:\\Program Files\\Git\\bin\\git.exe", "status"), status, cwd).family)
        assertEquals("generic", OutputCompressor.compress(listOf("java", "-cp", "C:/gradle-9.6.0/lib/x.jar:/tools/git status", "Main"), status, cwd).family)
        assertEquals("generic", OutputCompressor.compress(listOf("echo", "gradle-9.6.0"), recorded("gradle-test-fail.txt"), cwd).family)
    }

    @Test
    fun `repeated lines are folded and colour codes removed`() {
        val text = "\u001B[31mretrying\u001B[0m\n".repeat(400)
        assertEquals("retrying (×400)", OutputCompressor.compress(listOf("./wait.sh"), text).text)
    }

    @Test
    fun `test runners other than gradle give counts, failures and the last lines`() {
        val tap = (1..60).joinToString("\n") { "ok $it - case $it" } + "\nnot ok 61 - totals\n# tests 61\n# pass 60\n# fail 1\n"
        val out = OutputCompressor.compress(listOf("node", "--test"), tap).text
        assertContains(out, "tests 61, passed 60, failed 1")
        assertContains(out, "not ok 61 - totals")
        assertTrue(out.length < tap.length / 3)
    }
}
