package codeloupe.platform

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessPriorityTest {
    private val os = System.getProperty("os.name").lowercase()

    private fun <T> withProbe(observe: (pid: Long) -> T): T {
        val command = JavaProcess.command(PriorityProbe::class.java.name, listOf("-Xmx32m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"), emptyList())
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val pid = process.inputStream.bufferedReader().readLine().trim().toLong()
            Thread.sleep(300)
            return observe(pid)
        } finally {
            process.outputStream.close()
            process.destroyForcibly()
        }
    }

    // Field 19 of /proc/<pid>/task/<tid>/stat is the nice value; the name in parentheses may hold spaces, so the fields are counted after it.
    private fun niceOf(stat: Path): Int? =
        runCatching { Files.readString(stat).substringAfterLast(')').trim().split(' ')[NICE_FIELD].toInt() }.getOrNull()

    @Test
    fun `on Linux every thread of the process is lowered, the ones the JVM made before the call included`() {
        assumeTrue(os.startsWith("linux"), "Linux keeps a priority per thread")
        val niceByThread = withProbe { pid ->
            Files.newDirectoryStream(Path.of("/proc/$pid/task")).use { tasks -> tasks.mapNotNull { task -> niceOf(task.resolve("stat"))?.let { task.fileName.toString() to it } } }
        }
        assertTrue(niceByThread.size >= 5, "a JVM has more than a few threads: $niceByThread")
        assertEquals(emptyList(), niceByThread.filter { it.second != 10 }, "threads left at normal priority")
    }

    @Test
    fun `on macOS the process is lowered`() {
        assumeTrue(os.startsWith("mac"), "ps reports the nice value of a macOS process")
        val nice = withProbe { pid -> ToolOutput.read(10, "ps", "-o", "nice=", "-p", pid.toString()).trim().toInt() }
        assertEquals(10, nice)
    }

    @Test
    fun `on Windows the process runs below normal priority`() {
        assumeTrue(NativeCalls.isWindows, "priority classes are Windows only")
        val priority = withProbe { pid -> ToolOutput.read(30, "powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-Process -Id $pid).PriorityClass").trim() }
        assertEquals("BelowNormal", priority)
    }

    private companion object {
        const val NICE_FIELD = 16
    }
}
