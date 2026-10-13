package codeloupe.jobs

import codeloupe.index.ParseWorkerClient
import codeloupe.platform.JavaProcess
import codeloupe.platform.JobObjects
import java.nio.file.Path

/**
 * Plays a daemon that is about to die: enters its kill-on-close job like `codeloupe daemon`, starts a plain child and a parse worker
 * (what must die with it) and a job (what must outlive it), prints `inJob= plain= worker= job= survives=` and waits to be killed.
 * Arguments: the job's directory, log and exit file.
 */
object DaemonDeathProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val (work, log, exitFile) = args.map { Path.of(it) }
        val inJob = JobObjects.enterSelf()
        val plain = ProcessBuilder(FakeJob.command("sleep=120000")).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val worker = ParseWorkerClient(idleSeconds = 600).also { it.extract("A.kt", "class A") }
        val job = JobProcess.start(FakeJob.command("print=job running", "sleep=4000", "exit=7"), work, mapOf("PROBE_VALUE" to "1"), log, exitFile) { System.err.println(it) }
        println("inJob=$inJob plain=${plain.pid()} worker=${worker.pid} job=${job.pid} survives=${job.survives}")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }

    fun command(work: Path, log: Path, exitFile: Path): List<String> =
        JavaProcess.command(DaemonDeathProbe::class.java.name, listOf("-Xmx128m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"), listOf(work, log, exitFile).map(Path::toString))
}
