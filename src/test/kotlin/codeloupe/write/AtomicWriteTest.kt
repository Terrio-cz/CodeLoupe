package codeloupe.write

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicWriteTest {
    private fun names(dir: java.nio.file.Path) = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `a write removes the old temporary files of its own target and leaves recent ones and other files alone`() {
        val dir = Files.createTempDirectory("atomic-sweep")
        val file = dir.resolve("a.kt")
        file.writeText("old")
        val killed = dir.resolve(".a.kt.11111111-0000.codeloupe-tmp").also { it.writeText("half") }
        Files.setLastModifiedTime(killed, FileTime.fromMillis(System.currentTimeMillis() - AtomicWrite.STALE_MS - 60_000))
        val running = dir.resolve(".a.kt.22222222-0000.codeloupe-tmp").also { it.writeText("being written") }
        val others = dir.resolve(".b.kt.33333333-0000.codeloupe-tmp").also { it.writeText("another file's") }
        Files.setLastModifiedTime(others, FileTime.fromMillis(System.currentTimeMillis() - AtomicWrite.STALE_MS - 60_000))

        AtomicWrite.replace(file, "new".toByteArray())

        assertEquals("new", Files.readString(file))
        assertEquals(listOf(".a.kt.22222222-0000.codeloupe-tmp", ".b.kt.33333333-0000.codeloupe-tmp", "a.kt"), names(dir))
        assertTrue(Files.exists(running))
        assertFalse(Files.exists(killed))
    }

    @Test
    fun `the check just before the rename can stop the write, which leaves the target and no temporary file`() {
        val dir = Files.createTempDirectory("atomic-guard")
        val file = dir.resolve("a.kt")
        file.writeText("old")
        assertFailsWith<WriteRefused> { AtomicWrite.replace(file, "new".toByteArray()) { throw WriteRefused("a.kt changed") } }
        assertEquals("old", Files.readString(file))
        assertEquals(listOf("a.kt"), names(dir))
    }
}
