package codeloupe.secrets

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecTimeoutTest {
    private fun child(mode: String) =
        listOf(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"), "codeloupe.platform.ToolOutputChild", mode)

    @Test
    fun `a key-store tool that never closes its output is given up on at the deadline`() {
        val started = System.nanoTime()
        assertFailsWith<IllegalStateException> { Exec.run(child("hang"), timeoutSec = 2) }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(20), "the read was not bounded")
    }

    @Test
    fun `a tool that answers is read as before`() {
        val result = Exec.run(child("echo"), timeoutSec = 30)
        assertEquals(0, result.exit)
        assertEquals("hello", result.out)
    }
}
