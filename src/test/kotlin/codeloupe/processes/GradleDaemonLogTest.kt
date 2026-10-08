package codeloupe.processes

import codeloupe.TestRepos
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleDaemonLogTest {
    private val home = TestRepos.tmpDir("gradle-home")
    private val line = "org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.6.0"

    private fun log(pid: Long, vararg lines: String, version: String = "9.6.0") {
        val file = home.resolve("daemon").resolve(version).resolve("daemon-$pid.out.log")
        Files.createDirectories(file.parent)
        Files.write(file, lines.toList())
    }

    private fun build(dir: String) = "2026-10-08T20:32:54.240+0200 [INFO] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Received command: Build{id=58386527-49bc, currentDir=$dir}."

    private fun marking(state: String) = "2026-10-08T20:32:54.243+0200 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as $state, address: [79eb port:60009]"

    @Test
    fun `the last build directory and the last busy or idle mark of the log tell where a daemon built and whether it works`() {
        log(1, build("C:\\w\\TER-1"), marking("busy"), marking("idle"), build("C:\\w\\TER-2"), marking("busy"))
        val busy = GradleDaemonLog.read(line, 1, listOf(home))!!
        assertEquals("C:\\w\\TER-2", busy.lastBuildDir)
        assertTrue(busy.busy)

        log(2, build("/w/TER-1"), marking("busy"), marking("idle"))
        val idle = GradleDaemonLog.read(line, 2, listOf(home))!!
        assertEquals("/w/TER-1", idle.lastBuildDir)
        assertFalse(idle.busy)
    }

    @Test
    fun `a daemon that has not built yet is idle without a directory, and a missing log or another version is no answer`() {
        log(3, "2026-10-08T20:32:50.000+0200 [INFO] [org.gradle.launcher.daemon.server.Daemon] start() called on daemon")
        val fresh = GradleDaemonLog.read(line, 3, listOf(home))!!
        assertNull(fresh.lastBuildDir)
        assertFalse(fresh.busy)
        assertNull(GradleDaemonLog.read(line, 4, listOf(home)), "no log of that pid")
        assertNull(GradleDaemonLog.read("java -jar x.jar", 3, listOf(home)), "not a Gradle daemon command line")
        assertNull(GradleDaemonLog.read(line.replace("9.6.0", "8.0"), 3, listOf(home)), "the log of another version is not this daemon's")
        assertNull(GradleDaemonLog.read(line, 3, emptyList()))
    }

    @Test
    fun `only the end of a long log is read`() {
        val filler = "2026-10-08T20:00:00.000+0200 [DEBUG] [x] " + "y".repeat(200)
        log(5, build("/w/OLD"), marking("idle"), *Array(4_000) { filler }, build("/w/NEW"), marking("idle"))
        assertEquals("/w/NEW", GradleDaemonLog.read(line, 5, listOf(home))!!.lastBuildDir)
        log(6, build("/w/OLD"), marking("idle"), *Array(4_000) { filler })
        assertNull(GradleDaemonLog.read(line, 6, listOf(home))!!.lastBuildDir, "a build older than the part read is not guessed")
    }
}
