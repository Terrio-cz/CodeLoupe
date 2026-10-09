package codeloupe.processes

import codeloupe.TestRepos
import codeloupe.workspace.OrphanDirs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemProcessesTest {
    @Test
    fun `the OS lists a process with its working directory, command line, memory, start and CPU`() {
        val dir = TestRepos.tmpDir("proc-cwd")
        ChildProcesses().use { children ->
            val child = children.start(dir, GRADLE_DAEMON_MARKER)
            val listed = children.awaitListed(child)
            assertEquals(OrphanDirs.key(dir), OrphanDirs.key(java.nio.file.Path.of(listed.cwd!!)))
            assertTrue(GRADLE_DAEMON_MARKER in listed.commandLine!!, listed.commandLine)
            assertEquals(ProcessKind.GRADLE_DAEMON, listed.kind)
            assertTrue((listed.rssBytes ?: 0) > 1_000_000, "a JVM holds more than 1 MB: ${listed.rssBytes}")
            assertTrue(listed.startMs > 0)
        }
    }

    @Test
    fun `a working directory with spaces and non-ASCII letters is listed as it is`() {
        val dir = TestRepos.tmpDir("proc-cwd").resolve("work space ünï").also { java.nio.file.Files.createDirectories(it) }
        ChildProcesses().use { children ->
            val child = children.start(dir, GRADLE_DAEMON_MARKER)
            val listed = children.awaitListed(child)
            assertEquals(OrphanDirs.key(dir), OrphanDirs.key(java.nio.file.Path.of(listed.cwd!!)))
        }
    }

    @Test
    fun `this process is listed too, and a command line tells the build tools apart`() {
        val me = SystemProcesses().read().firstOrNull { it.pid == ProcessHandle.current().pid() }
        assertNotNull(me)
        assertEquals(ProcessKind.GRADLE_DAEMON, ProcessKind.of("java -cp x org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.6.0"))
        assertEquals(ProcessKind.GRADLE_WORKER, ProcessKind.of("java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 3'"))
        assertEquals(ProcessKind.KOTLIN_DAEMON, ProcessKind.of("java org.jetbrains.kotlin.daemon.KotlinCompileDaemon --daemon-runFilesPath x"))
        assertEquals(ProcessKind.GRADLE_CLIENT, ProcessKind.of("java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain test"))
        assertEquals(ProcessKind.OTHER, ProcessKind.of("node server.js"))
        assertEquals(ProcessKind.OTHER, ProcessKind.of("claude -p \"why does KotlinCompileDaemon idle? see GradleWorkerMain.kt\""), "a prompt that names a build tool is not one")
        assertEquals(ProcessKind.OTHER, ProcessKind.of("node tick.js GradleWorkerMain.kt"))
        assertTrue(ProcessKind.GRADLE_DAEMON.buildTool && ProcessKind.GRADLE_WORKER.buildTool && ProcessKind.KOTLIN_DAEMON.buildTool)
        assertTrue(!ProcessKind.GRADLE_CLIENT.buildTool && !ProcessKind.OTHER.buildTool)
    }
}
