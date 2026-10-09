package codeloupe.platform

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tool the way `lsof` on a stale mount behaves: it never ends and never closes its output. */
object ToolOutputChild {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "hang") Thread.sleep(120_000) else print("hello")
    }
}

class ToolOutputTest {
    private fun java(mode: String): Array<String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        return arrayOf(java, "-cp", System.getProperty("java.class.path"), "codeloupe.platform.ToolOutputChild", mode)
    }

    @Test
    fun `the output of a tool that ends is returned`() {
        assertEquals("hello", ToolOutput.read(30, *java("echo")))
    }

    @Test
    fun `a tool that never ends is given up on at the deadline`() {
        val started = System.nanoTime()
        assertEquals("", ToolOutput.read(2, *java("hang")))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(20), "the caller waited for a tool that does not end")
    }

    @Test
    fun `a tool that is not installed gives nothing`() {
        assertEquals("", ToolOutput.read(5, "codeloupe-no-such-tool-anywhere"))
    }
}
