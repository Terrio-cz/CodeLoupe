package codeloupe.jobs

import codeloupe.TestRepos
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CL-244: on Windows a job outlives the daemon that started it, and only the job; its exit code survives the gap. */
class JobSurvivalTest {
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    @Test
    fun `a killed daemon takes its parse worker and other children along, never its job, whose exit code is kept`() {
        assumeTrue(windows, "job objects exist on Windows only")
        val work = TestRepos.tmpDir("survival-work")
        val log = work.resolve("job.log")
        val exitFile = work.resolve("job.exit")
        val probe = ProcessBuilder(DaemonDeathProbe.command(work, log, exitFile)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val pids = mutableMapOf<String, Long>()
        try {
            val line = CompletableFuture.supplyAsync { probe.inputReader().readLine() }.get(60, TimeUnit.SECONDS)
            val fields = line.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("true", fields["inJob"], "the probe sits in its own kill-on-close job: $line")
            assertEquals("true", fields["survives"], "the job has its watcher: $line")
            listOf("plain", "worker", "job").forEach { pids[it] = fields.getValue(it).toLong() }

            probe.destroyForcibly()
            assertTrue(probe.waitFor(20, TimeUnit.SECONDS))
            assertTrue(eventually(20_000) { !alive(pids.getValue("plain")) && !alive(pids.getValue("worker")) }, "the parse worker and the plain child end with the daemon")
            assertTrue(alive(pids.getValue("job")), "the job runs on")

            assertTrue(eventually(30_000) { ExitFile.read(exitFile) != null }, "the watcher writes the exit code once the job ends")
            assertEquals(7, ExitFile.read(exitFile), "the job's real exit code")
            assertFalse(alive(pids.getValue("job")))
            assertContains(Files.readString(log), "job running")
        } finally {
            probe.destroyForcibly()
            pids.values.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test
    fun `cancelling a job ends it and its watcher, and no exit code is written`() {
        assumeTrue(windows, "job objects exist on Windows only")
        val work = TestRepos.tmpDir("survival-cancel")
        val exitFile = work.resolve("job.exit")
        val watchers = { ProcessHandle.current().children().filter { it.info().command().orElse("").endsWith("powershell.exe", ignoreCase = true) }.toList().toSet() }
        val before = watchers()
        val job = JobProcess.start(FakeJob.command("sleep=60000"), work, emptyMap(), work.resolve("job.log"), exitFile)
        assertTrue(job.survives)
        val watcher = (watchers() - before).single()
        job.kill()
        assertTrue(job.onExit().get(20, TimeUnit.SECONDS) != null)
        assertTrue(eventually(10_000) { !watcher.isAlive }, "the watcher ends with the job's object")
        assertEquals(null, ExitFile.read(exitFile))
        job.release()?.close()
    }

    @Test
    fun `a job started in its own job object runs batch files, gets its environment and appends to its log like ProcessBuilder`() {
        assumeTrue(windows, "job objects exist on Windows only")
        val dir = TestRepos.tmpDir("survival batch dir")
        Files.writeString(dir.resolve("tool.cmd"), "@echo off\r\necho batch %1 %JOB_VALUE%\r\nexit /b 4\r\n")
        val log = dir.resolve("job.log").also { Files.writeString(it, "before\n") }
        val job = JobProcess.start(listOf("./tool", "first"), dir, mapOf("JOB_VALUE" to "from env"), log, dir.resolve("job.exit"))
        assertTrue(job.survives)
        job.onExit().get(30, TimeUnit.SECONDS)
        assertEquals(4, job.exitValue())
        assertEquals(listOf("before", "batch first from env"), Files.readAllLines(log).map { it.trim() })
        assertTrue(eventually(10_000) { ExitFile.read(dir.resolve("job.exit")) == 4 })
        job.release()?.close()
    }

    @Test
    fun `a program that does not exist fails as the JDK says it`() {
        assumeTrue(windows, "job objects exist on Windows only")
        val dir = TestRepos.tmpDir("survival-missing")
        val error = runCatching { JobProcess.start(listOf("no-such-program-cl244"), dir, emptyMap(), dir.resolve("job.log"), dir.resolve("job.exit")) }.exceptionOrNull()
        assertContains(error?.message.orEmpty(), "error=2")
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun eventually(ms: Long, check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }
}
