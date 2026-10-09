package codeloupe.platform

import codeloupe.TestRepos
import codeloupe.jobs.FakeJob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetachedStartTest {
    @Test
    fun `arguments are quoted the way the Windows C runtime splits them`() {
        assertEquals("plain", DetachedStart.quote("plain"))
        assertEquals("\"a b\"", DetachedStart.quote("a b"))
        assertEquals("\"\"", DetachedStart.quote(""))
        assertEquals("\"say \\\"hi\\\"\"", DetachedStart.quote("say \"hi\""))
        assertEquals("\"C:\\dir with space\\\\\"", DetachedStart.quote("C:\\dir with space\\"))
    }

    @Test
    fun `a daemon started from a shell outside Windows keeps where it is told to look for things, and nothing that changes what programs do`() {
        for (name in listOf("HOME", "PATH", "LC_ALL", "DOCKER_HOST", "GRADLE_USER_HOME", "XDG_CACHE_HOME", "DBUS_SESSION_BUS_ADDRESS", "CODELOUPE_PASSPHRASE", "CODELOUPE_HOME")) {
            assertTrue(DetachedStart.kept(name), name)
        }
        for (name in listOf("LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "NODE_OPTIONS", "JAVA_TOOL_OPTIONS", "GIT_SSH_COMMAND", "ANTHROPIC_API_KEY")) {
            assertTrue(!DetachedStart.kept(name), name)
        }
    }

    @Test
    fun `the environment of a daemon started outside Windows is cut down to what is kept`() {
        val environment = mutableMapOf("HOME" to "/home/u", "PATH" to "/usr/bin", "LC_CTYPE" to "C.UTF-8", "CODELOUPE_HOME" to "/h", "LD_PRELOAD" to "/x.so", "NODE_OPTIONS" to "--require x", "ANTHROPIC_API_KEY" to "k")
        DetachedStart.cut(environment)
        assertEquals(setOf("HOME", "PATH", "LC_CTYPE", "CODELOUPE_HOME"), environment.keys)
    }

    @Test
    fun `outside Windows the process is started as a plain child with the cut environment and runs on`() {
        assumeTrue(!NativeCalls.isWindows, "Windows starts it through WMI instead")
        val dir = TestRepos.tmpDir("detached-posix")
        val marker = dir.resolve("marker with space.txt")
        DetachedStart.start(FakeJob.command("sleep=300", "touch=$marker"), dir)
        repeat(100) {
            if (Files.exists(marker)) return
            Thread.sleep(100)
        }
        assertTrue(Files.exists(marker), "the detached process ran")
    }

    @Test
    fun `on Windows the process is started by WMI, not as a child of the caller`() {
        assumeTrue(NativeCalls.isWindows, "WMI exists on Windows only")
        val dir = TestRepos.tmpDir("detached")
        val marker = dir.resolve("marker with space.txt")
        DetachedStart.start(FakeJob.command("sleep=1500", "touch=$marker"), dir)
        val ours = ProcessHandle.current().descendants().anyMatch { h -> h.info().commandLine().map { "touch=" in it }.orElse(false) }
        assertTrue(!ours, "not our descendant: outside the caller's job object")
        repeat(100) {
            if (Files.exists(marker)) return
            Thread.sleep(100)
        }
        assertTrue(Files.exists(marker), "the detached process ran")
    }
}
