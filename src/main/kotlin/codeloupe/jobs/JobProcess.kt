package codeloupe.jobs

import codeloupe.platform.JobObjects
import java.io.File
import java.nio.file.Path

/**
 * A job's OS process: output and errors appended to its log file by the OS (the daemon pumps no pipes and the job
 * does not depend on the daemon to write), no stdin, and on Windows a job object of its own for cancelling the tree.
 */
class JobProcess private constructor(val process: Process, private val tree: JobObjects.Tree?) {
    /** Ends the job and everything it started. */
    fun kill() {
        tree?.terminate()
        kill(process.toHandle())
    }

    /** The job ended: what it left running (a build daemon) runs on, still inside the daemon's job object. */
    fun release() = tree?.close()

    companion object {
        private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

        // cmd.exe re-reads a batch file's command line: Java quotes only blanks, so these would run as commands.
        private val CMD_SPECIAL = Regex("""[&|<>^%!"()\r\n]""")

        /** Why [command] cannot run as given, or null. */
        fun problem(command: List<String>, cwd: Path, env: Map<String, String>): String? {
            val program = Executables.resolve(command.first(), cwd, environment(env), windows)
            if (!windows || !Executables.isBatch(program)) return null
            val bad = command.drop(1).firstOrNull { CMD_SPECIAL.containsMatchIn(it) } ?: return null
            return "${command.first()} is a batch file, which cmd.exe would parse again: argument \"$bad\" holds one of & | < > ^ % ! \" ( ). " +
                "Run the program it wraps, or bash -c '…'"
        }

        fun start(command: List<String>, cwd: Path, env: Map<String, String>, log: Path): JobProcess {
            problem(command, cwd, env)?.let { throw IllegalArgumentException(it) }
            val builder = ProcessBuilder()
            val environment = builder.environment().apply { putAll(env) }
            builder.command(listOf(Executables.resolve(command.first(), cwd, environment, windows)) + command.drop(1))
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(File(if (windows) "NUL" else "/dev/null")))
            val process = builder.start()
            return JobProcess(process, JobObjects.forProcess(process.pid()))
        }

        /** Ends [process] and those of its descendants that still know it as their ancestor. */
        fun kill(process: ProcessHandle) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }

        // ProcessBuilder's map: case-insensitive on Windows, like the OS (Path = PATH).
        private fun environment(env: Map<String, String>): Map<String, String> = ProcessBuilder().environment().apply { putAll(env) }
    }
}
