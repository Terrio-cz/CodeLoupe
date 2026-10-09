package codeloupe.write

import codeloupe.TestRepos
import codeloupe.config.WriteConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All-or-none writes: a failure half way, an old file that cannot be removed, a journal that cannot be written. */
class WriteApplierTest {
    private val worktree = TestRepos.tmpDir("applier").resolve("wt").createDirectories()
    private val main = TestRepos.tmpDir("applier-main")
    private val journalFile = worktree.resolveSibling("writes.jsonl")

    private fun applier(journal: WriteJournal = WriteJournal(journalFile)) = WriteApplier(WritePolicy(WriteConfig()), journal)

    private fun source(path: String, text: String): SourceText = worktree.resolve(path).also { it.parent.createDirectories(); it.writeText(text) }.let { SourceText.read(it)!! }

    @Test
    fun `a file that cannot be created puts the files already written back`() {
        val a = source("A.kt", "class A\n")
        worktree.resolve("blocker").writeText("a regular file where a directory is needed")
        val changes = listOf(FileChange("A.kt", a, "class A2\n"), FileChange("blocker/New.kt", null, "class New\n"))

        assertFailsWith<WriteRefused> { applier().apply("test", "r", worktree, main, changes, "n") }

        assertEquals("class A\n", worktree.resolve("A.kt").readText())
        assertEquals(emptyList(), WriteJournal(journalFile).read(), "a write that did not happen is not logged")
    }

    @Test
    fun `an old file that cannot be removed leaves no copy under the new name`() {
        assumeTrue(System.getProperty("os.name").lowercase().startsWith("windows"), "a read-only file is undeletable on Windows only")
        val old = source("Old.kt", "class Old\n")
        worktree.resolve("Old.kt").toFile().setReadOnly()

        try {
            assertFailsWith<WriteRefused> { applier().apply("rename", "r", worktree, main, listOf(FileChange("Old.kt", old, "class New\n", movedTo = "New.kt")), "n") }
            assertFalse(worktree.resolve("New.kt").exists(), "half of a move was left behind")
            assertEquals("class Old\n", worktree.resolve("Old.kt").readText())
        } finally {
            worktree.resolve("Old.kt").toFile().setWritable(true)
        }
    }

    @Test
    fun `an old file in a folder that cannot be changed leaves no copy under the new name`() {
        val old = source("old/Old.kt", "class Old\n")
        val folder = worktree.resolve("old")
        folder.toFile().setWritable(false)
        try {
            assumeTrue(!Files.isWritable(folder), "this user can change a read-only folder (root, or a file system without such modes)")
            assertFailsWith<WriteRefused> { applier().apply("rename", "r", worktree, main, listOf(FileChange("old/Old.kt", old, "class New\n", movedTo = "new/New.kt")), "n") }
            assertFalse(worktree.resolve("new/New.kt").exists(), "half of a move was left behind")
            assertEquals("class Old\n", worktree.resolve("old/Old.kt").readText())
        } finally {
            folder.toFile().setWritable(true)
        }
    }

    @Test
    fun `a write that cannot be logged is not kept`() {
        val a = source("A.kt", "class A\n")
        val journal = WriteJournal(worktree.resolveSibling("journal-dir").also { it.createDirectories() })

        assertFailsWith<WriteRefused> { applier(journal).apply("test", "r", worktree, main, listOf(FileChange("A.kt", a, "class A2\n")), "n") }

        assertEquals("class A\n", worktree.resolve("A.kt").readText())
    }

    @Test
    fun `a file changed since it was read is refused before anything is written`() {
        val a = source("A.kt", "class A\n")
        val b = source("B.kt", "class B\n")
        worktree.resolve("B.kt").writeText("class B changed\n")

        assertFailsWith<WriteRefused> { applier().apply("test", "r", worktree, main, listOf(FileChange("A.kt", a, "class A2\n"), FileChange("B.kt", b, "class B2\n")), "n") }

        assertEquals("class A\n", worktree.resolve("A.kt").readText())
        assertTrue(Files.list(worktree).use { s -> s.noneMatch { it.fileName.toString().endsWith(".codeloupe-tmp") } })
    }

    @Test
    fun `a path leaving the worktree is refused`() {
        val outside: Path = worktree.resolveSibling("Outside.kt")
        assertFailsWith<WriteRefused> { applier().apply("test", "r", worktree, main, listOf(FileChange("../Outside.kt", null, "class O\n")), "n") }
        assertFalse(outside.exists())
    }
}
