package codeloupe.platform

import codeloupe.jobs.FakeJob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobObjectsTest {
    @Test
    fun `outside Windows there is no job object and nothing is claimed`() {
        assumeTrue(!NativeCalls.isWindows, "Windows has job objects")
        assertEquals(false, JobObjects.enterSelf())
        assertNull(JobObjects.forProcess(ProcessHandle.current().pid()))
    }

    @Test
    fun `on Windows ending the job of a process ends the process`() {
        assumeTrue(NativeCalls.isWindows, "job objects exist on Windows only")
        val process = ProcessBuilder(FakeJob.command("sleep=60000")).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val tree = assertNotNull(JobObjects.forProcess(process.pid()), "the OS gave a job object")
            tree.use { it.terminate() }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "the process ended with its job")
        } finally {
            process.destroyForcibly()
        }
    }
}
