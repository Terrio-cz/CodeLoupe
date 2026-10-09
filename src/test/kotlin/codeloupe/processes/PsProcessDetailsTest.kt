package codeloupe.processes

import kotlin.test.Test
import kotlin.test.assertEquals

/** What macOS prints, read on every OS. */
class PsProcessDetailsTest {
    @Test
    fun `ps gives resident sizes by pid, in bytes, and ignores lines it cannot read`() {
        val text = "    1   9120\n  4242 512000\n 77001  0\nPID RSS\nnot a line\n"
        assertEquals(mapOf(1L to 9120L * 1024, 4242L to 512_000L * 1024, 77001L to 0L), PsProcessDetails.residentBytes(text))
    }

    @Test
    fun `lsof gives the working directory under each pid, with spaces and without a trailing newline`() {
        val text = "p100\nn/Users/me/work space/app\np200\nn/\np300\nn/Users/me/ünï"
        assertEquals(mapOf(100L to "/Users/me/work space/app", 200L to "/", 300L to "/Users/me/ünï"), PsProcessDetails.workingDirectories(text))
    }

    @Test
    fun `lsof under the C locale escapes the bytes of a non-ASCII name and they are read back as UTF-8`() {
        assertEquals("/Users/me/café", PsProcessDetails.unescape("/Users/me/caf" + "\\xc3\\xa9"))
        assertEquals("/tmp/日本", PsProcessDetails.unescape("/tmp/" + "\\xe6\\x97\\xa5" + "\\xe6\\x9c\\xac"))
        assertEquals(mapOf(7L to "/tmp/ñ x"), PsProcessDetails.workingDirectories("p7\nn/tmp/" + "\\xc3\\xb1" + " x\n"))
        assertEquals("/plain/path", PsProcessDetails.unescape("/plain/path"))
    }
}
