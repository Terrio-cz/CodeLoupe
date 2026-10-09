package codeloupe.processes

import codeloupe.TestRepos
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcFsProcessDetailsTest {
    @Test
    fun `a command line is the arguments of the NUL separated file with spaces and non-ASCII kept`() {
        val raw = "java\u0000-cp\u0000/work space/ünï\u0000Main\u0000".toByteArray(Charsets.UTF_8)
        assertEquals("java -cp /work space/ünï Main", ProcFsProcessDetails.commandLine(raw))
        assertEquals("", ProcFsProcessDetails.commandLine(ByteArray(0)))
    }

    @Test
    fun `a working directory Linux marks as deleted is read as the path it had, unless a directory of that name exists`() {
        assertEquals("/ws/TER-5", ProcFsProcessDetails.withoutDeleted("/ws/TER-5 (deleted)"))
        assertEquals("/ws/TER-5", ProcFsProcessDetails.withoutDeleted("/ws/TER-5"))
        val odd = TestRepos.tmpDir("procfs-odd").resolve("x (deleted)").also { Files.createDirectories(it) }
        assertEquals(odd.toString(), ProcFsProcessDetails.withoutDeleted(odd.toString()))
    }

    @Test
    fun `a process whose directory was removed under it is still listed in that directory`() {
        assumeTrue(ProcFsProcessDetails.available, "no /proc on this system")
        val dir = TestRepos.tmpDir("procfs-gone").resolve("workspace").also { Files.createDirectories(it) }
        ChildProcesses().use { children ->
            val child = children.start(dir, GRADLE_DAEMON_MARKER)
            awaitDetails(child.pid(), dir.toRealPath().toString())
            Files.delete(dir)
            val gone = awaitDetails(child.pid(), dir.toString())
            assertEquals(dir.toString(), Path.of(assertNotNull(gone.cwd)).toString())
        }
    }

    @Test
    fun `the working directory of a process with spaces and non-ASCII letters in its path is read as it is`() {
        assumeTrue(ProcFsProcessDetails.available, "no /proc on this system")
        val dir = TestRepos.tmpDir("procfs").resolve("work space ünï").also { Files.createDirectories(it) }
        ChildProcesses().use { children ->
            val child = children.start(dir, GRADLE_DAEMON_MARKER)
            val details = awaitDetails(child.pid(), dir.toRealPath().toString())
            assertTrue(GRADLE_DAEMON_MARKER in assertNotNull(details.commandLine), details.commandLine)
            assertTrue((details.rssBytes ?: 0) > 1_000_000)
        }
    }

    // The command line is there from the exec, the memory only once the JVM has mapped its heap, and the directory is the wanted one
    // once the launcher has changed into it.
    private fun awaitDetails(pid: Long, cwd: String): ProcessDetails {
        val until = System.currentTimeMillis() + 30_000
        var last: ProcessDetails? = null
        while (System.currentTimeMillis() < until) {
            last = ProcFsProcessDetails.read(pid)
            last?.takeIf { it.cwd?.let(Path::of) == Path.of(cwd) && it.commandLine?.contains(GRADLE_DAEMON_MARKER) == true && (it.rssBytes ?: 0) > 1_000_000 }?.let { return it }
            Thread.sleep(100)
        }
        error("pid $pid was not read from /proc as in $cwd: cwd ${last?.cwd}, command line ${last?.commandLine}, rss ${last?.rssBytes}")
    }
}
