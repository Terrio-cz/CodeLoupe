package codeloupe.jobs

import codeloupe.TestRepos
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A finished job's log loses the stored values and nothing else. */
class LogMaskTest {
    private val dir = TestRepos.tmpDir("logmask")

    @Test
    fun `values are replaced byte exactly, other bytes and line endings stay`() {
        val log = dir.resolve("a.log")
        val odd = byteArrayOf(0xE9.toByte(), 0xFF.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        Files.write(log, "start\r\ntoken=hunter2-secret-VALUE end\n".toByteArray() + odd + "no newline at the end hunter2-secret-VALUE".toByteArray())
        LogMask.apply(log, listOf("hunter2-secret-VALUE", "short"))
        val bytes = Files.readAllBytes(log)
        assertContentEquals("start\r\ntoken=*** end\n".toByteArray() + odd + "no newline at the end ***".toByteArray(), bytes)
    }

    @Test
    fun `a multi-line secret is masked line by line, a short one and a clean log are left alone`() {
        val key = "-----BEGIN KEY-----\nabcdefghijklmnop\n-----END KEY-----"
        val log = dir.resolve("b.log")
        Files.writeString(log, "before\nabcdefghijklmnop\nafter\n")
        LogMask.apply(log, listOf(key))
        assertEquals("before\n***\nafter\n", Files.readString(log))
        val clean = dir.resolve("c.log")
        Files.writeString(clean, "nothing here\n")
        val before = Files.getLastModifiedTime(clean)
        LogMask.apply(clean, listOf("elsewhere-value"))
        assertEquals("nothing here\n", Files.readString(clean))
        assertEquals(before, Files.getLastModifiedTime(clean), "an unchanged log is not rewritten")
        val short = dir.resolve("d.log")
        Files.writeString(short, "tiny and abc\n")
        LogMask.apply(short, listOf("tiny"))
        assertFalse("***" in Files.readString(short))
    }

    @Test
    fun `a large log is processed in a stream`() {
        val log = dir.resolve("big.log")
        Files.newBufferedWriter(log).use { w -> repeat(200_000) { w.write("progress line number $it with some padding to make it longer\n") }; w.write("end secret-in-big-log-0001\n") }
        LogMask.apply(log, listOf("secret-in-big-log-0001"))
        assertEquals("end ***", Files.readAllLines(log).last())
    }
}
