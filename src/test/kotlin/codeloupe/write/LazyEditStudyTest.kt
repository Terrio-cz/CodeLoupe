package codeloupe.write

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The study on a small history that is built here, and on a real one when CODELOUPE_LAZY_STUDY names a repository. */
class LazyEditStudyTest {
    private fun file(members: String) = "package demo\n\nclass Billing {\n$members}\n"

    private val a = "    fun a(): Int = 1\n"
    private val b = "    fun b(x: Int): Int {\n        return x\n    }\n"
    private val c = "    fun c(): Int = 3\n"
    private val d = "    fun d(): Int = 4\n"

    @Test
    fun `an edit of two members of a type in a built history is found, measured and reproduced`() {
        val repo = TestRepos.tmpDir("lazy-study")
        git(repo, "init", "-q", "-b", "main")
        git(repo, "config", "user.email", "d@e.x")
        git(repo, "config", "user.name", "d")
        val path = repo.resolve("src/main/kotlin/Billing.kt")
        path.parent.createDirectories()
        path.writeText(file("$a\n$b\n$c\n$d"))
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "CL-1 start")
        path.writeText(file("$a\n${b.replace("return x", "return x * 2")}\n$c\n$d\n    fun e(): Int = 5\n"))
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "CL-2 change b and add e")
        val rows = LazyEditStudy(repo).run()
        assertEquals(1, rows.size, rows.joinToString { it.note })
        val row = rows.single()
        assertEquals(1, row.changed)
        assertEquals(1, row.added)
        assertTrue(row.reproduced, "the merge reproduces the real edit: ${row.note}")
        assertTrue(row.lazy < row.rewrite)
        assertTrue("| Billing.kt | Billing | 1 + 1 |" in LazyEditStudy.table(rows))
    }

    @Test
    fun `the study of a real repository is written out when asked for`() {
        val target = System.getenv("CODELOUPE_LAZY_STUDY")
        assumeTrue(target != null, "set CODELOUPE_LAZY_STUDY to a repository path")
        val rows = LazyEditStudy(Path.of(target!!)).run()
        System.getenv("CODELOUPE_LAZY_STUDY_OUT")?.let { Files.writeString(Path.of(it), LazyEditStudy.table(rows)) }
        assertTrue(rows.isNotEmpty())
    }
}
