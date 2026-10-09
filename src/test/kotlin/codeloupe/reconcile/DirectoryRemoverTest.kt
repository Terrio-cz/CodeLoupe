package codeloupe.reconcile

import codeloupe.TestRepos
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectoryRemoverTest {
    private val root = TestRepos.tmpDir("remover")

    private fun target(): Path = root.resolve("target").createDirectories().also {
        it.resolve("keep.txt").writeText("precious")
        it.resolve("deeper").createDirectories().resolve("also.txt").writeText("precious")
    }

    // A directory link: a junction on Windows (no privilege needed), a symlink elsewhere.
    private fun link(at: Path, to: Path) {
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", at.toString(), to.toString()).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            assumeTrue(process.waitFor() == 0, "cannot create a junction here")
        } else {
            Files.createSymbolicLink(at, to)
        }
    }

    @Test
    fun `a link inside the tree is removed and its target is left alone`() {
        val target = target()
        val victim = root.resolve("worktree").createDirectories()
        victim.resolve("file.txt").writeText("x")
        link(victim.resolve("shared"), target)

        DirectoryRemover.remove(victim)

        assertFalse(victim.exists(LinkOption.NOFOLLOW_LINKS))
        assertEquals("precious", target.resolve("keep.txt").let { Files.readString(it) })
        assertTrue(target.resolve("deeper/also.txt").exists())
    }

    @Test
    fun `a link as the directory itself is removed and its target is left alone`() {
        val target = target()
        val victim = root.resolve("linked")
        link(victim, target)

        DirectoryRemover.remove(victim)

        assertFalse(victim.exists(LinkOption.NOFOLLOW_LINKS))
        assertTrue(target.resolve("keep.txt").exists())
        assertTrue(target.resolve("deeper/also.txt").exists())
    }

    @Test
    fun `read-only files do not stop the removal`() {
        val victim = root.resolve("readonly").createDirectories()
        val file = victim.resolve("object").also { it.writeText("x") }
        file.toFile().setReadOnly()

        DirectoryRemover.remove(victim)

        assertFalse(victim.exists())
    }

    @Test
    fun `a directory that is already gone is not an error`() {
        DirectoryRemover.remove(root.resolve("never-was"))
    }
}
