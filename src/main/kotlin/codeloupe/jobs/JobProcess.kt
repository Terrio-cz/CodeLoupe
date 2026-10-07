package codeloupe.jobs

import codeloupe.platform.KillOnCloseJob
import java.io.File
import java.nio.file.Path

/**
 * A job's OS process: output and errors appended to its log file by the OS (the daemon pumps no pipes and the job
 * does not depend on the daemon to write), no stdin, and on Windows inside the daemon's kill-on-close job object.
 */
object JobProcess {
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    fun start(command: List<String>, cwd: Path, env: Map<String, String>, log: Path): Process {
        val builder = ProcessBuilder()
        val environment = builder.environment().apply { putAll(env) }
        builder.command(listOf(Executables.resolve(command.first(), cwd, environment, windows)) + command.drop(1))
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectInput(ProcessBuilder.Redirect.from(File(if (windows) "NUL" else "/dev/null")))
        return builder.start().also { KillOnCloseJob.assign(it.pid()) }
    }

    /** Ends [process] and everything it started that is still its descendant. */
    fun kill(process: ProcessHandle) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
