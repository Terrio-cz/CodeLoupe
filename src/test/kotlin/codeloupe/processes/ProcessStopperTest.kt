package codeloupe.processes

import codeloupe.TestRepos
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessStopperTest {
    private val root = TestRepos.tmpDir("stopper")
    private val workspace = root.resolve("TER-5").createDirectories()
    private val other = root.resolve("TER-6").createDirectories()

    private fun stopper(children: ChildProcesses) = ProcessStopper(children.world(), probeMs = 400, graceMs = 10_000)

    private fun stop(children: ChildProcesses, process: Process, path: java.nio.file.Path = workspace, settle: Boolean = true): ProcessStopper.Outcome {
        val listed = children.awaitListed(process, settle = settle)
        return stopper(children).stop(listed.pid, listed.startMs, path.toString().replace('\\', '/'))
    }

    @Test
    fun `an idle Gradle daemon of the workspace is stopped`() {
        ChildProcesses().use { children ->
            val daemon = children.start(workspace, GRADLE_DAEMON_MARKER)
            assertEquals(ProcessStopper.Outcome.Stopped, stop(children, daemon))
            assertTrue(daemon.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a daemon that is working is left alone and reported as busy`() {
        ChildProcesses().use { children ->
            val daemon = children.start(workspace, GRADLE_DAEMON_MARKER, spinMs = 60_000)
            val outcome = stop(children, daemon, settle = false)
            assertIs<ProcessStopper.Outcome.Blocked>(outcome)
            assertTrue(outcome.reason.startsWith("busy"), outcome.reason)
            assertTrue(daemon.isAlive)
        }
    }

    @Test
    fun `a build running in the workspace blocks the stop of its daemon, a build elsewhere does not`() {
        ChildProcesses().use { children ->
            val daemon = children.start(workspace, GRADLE_DAEMON_MARKER)
            val elsewhere = children.start(other, GRADLE_CLIENT_MARKER)
            children.awaitListed(elsewhere)
            assertEquals(ProcessStopper.Outcome.Stopped, stop(children, daemon))

            val second = children.start(workspace, GRADLE_DAEMON_MARKER)
            val client = children.start(workspace, GRADLE_CLIENT_MARKER)
            children.awaitListed(client)
            val outcome = stop(children, second)
            assertIs<ProcessStopper.Outcome.Blocked>(outcome)
            assertEquals("a Gradle build is running", outcome.reason)
            assertTrue(second.isAlive)
        }
    }

    @Test
    fun `the Kotlin daemon waits for every build, wherever it runs`() {
        ChildProcesses().use { children ->
            val kotlin = children.start(workspace, KOTLIN_DAEMON_MARKER)
            val client = children.start(other, GRADLE_CLIENT_MARKER)
            children.awaitListed(client)
            assertIs<ProcessStopper.Outcome.Blocked>(stop(children, kotlin))
            assertTrue(kotlin.isAlive)
            client.destroyForcibly().waitFor()
            assertEquals(ProcessStopper.Outcome.Stopped, stop(children, kotlin))
        }
    }

    @Test
    fun `a process that moved to another workspace, is no build tool, or is gone is not stopped`() {
        ChildProcesses().use { children ->
            val moved = children.start(other, GRADLE_DAEMON_MARKER)
            val outcome = stop(children, moved, workspace)
            assertIs<ProcessStopper.Outcome.Blocked>(outcome)
            assertEquals("no longer works in the workspace", outcome.reason)
            assertTrue(moved.isAlive)

            val plain = children.start(workspace, "just-a-program")
            assertIs<ProcessStopper.Outcome.Blocked>(stop(children, plain))
            assertTrue(plain.isAlive)

            val listed = children.awaitListed(moved)
            assertEquals(ProcessStopper.Outcome.Gone, stopper(children).stop(listed.pid, listed.startMs + 1, other.toString()), "the same pid with another start time is another process")
            assertEquals(ProcessStopper.Outcome.Gone, stopper(children).stop(Long.MAX_VALUE / 2, 1, workspace.toString()))
            assertTrue(moved.isAlive)
        }
    }
}
