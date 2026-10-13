package codeloupe.jobs

import codeloupe.platform.DetachedStart
import codeloupe.platform.JobObjects
import codeloupe.platform.WindowsProcesses
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * A job's OS process: output and errors appended to its log file by the OS (the daemon pumps no pipes and the job
 * does not depend on the daemon to write), no stdin, and on Windows a job object of its own for cancelling the tree.
 *
 * On Windows a job [survives] its daemon: it is created outside the daemon's kill-on-close job, in a kill-on-close job object
 * of its own that a watcher ([ExitFile]) keeps open while the job runs. A daemon that stops leaves it to the next one, which
 * takes it over; whatever ends the watcher ends the job, so it never runs on without an owner.
 */
class JobProcess private constructor(
    private val handle: ProcessHandle,
    private val exitCode: () -> Int?,
    private val tree: JobObjects.Tree?,
    private val closeHandles: () -> Unit,
    /** Runs on when this daemon ends, for the next daemon to take over. */
    val survives: Boolean,
) {
    val pid: Long get() = handle.pid()

    /** Start time of [pid], as [JobRecord.pidStart] holds it. */
    val startedAt: String? get() = handle.info().startInstant().map(Instant::toString).orElse(null)

    val isAlive: Boolean get() = handle.isAlive

    fun onExit(): CompletableFuture<ProcessHandle> = handle.onExit()

    /** Its exit code once it ended. */
    fun exitValue(): Int = exitCode() ?: error("the exit code of job process $pid cannot be read")

    /** Ends the job and everything it started. */
    fun kill() {
        tree?.terminate()
        kill(handle)
    }

    /**
     * The job ended. What it left running (a build daemon) runs on until this daemon ends: a kill-on-close job object is handed back
     * for the daemon to keep open, any other is closed (its processes then stay in the daemon's own job).
     */
    fun release(): JobObjects.Tree? {
        closeHandles()
        if (tree?.killOnClose == true) return tree
        tree?.close()
        return null
    }

    /** The daemon stops and leaves the job to the next one: drops this daemon's handles, ends nothing (the watcher holds the job). */
    fun leave() {
        closeHandles()
        tree?.close()
    }

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

        /** Starts [command]; on Windows its exit code also goes to [exitFile] (see [ExitFile]). [warn] hears why a job will not survive its daemon. */
        fun start(command: List<String>, cwd: Path, env: Map<String, String>, log: Path, exitFile: Path, warn: (String) -> Unit = {}): JobProcess {
            problem(command, cwd, env)?.let { throw IllegalArgumentException(it) }
            val environment = environment(env)
            val argv = listOf(Executables.resolve(command.first(), cwd, environment, windows)) + command.drop(1)
            if (windows) {
                try {
                    owned(argv, cwd, environment, log, exitFile, warn)?.let { return it }
                    warn("the OS refused a job object of its own; it ends with the daemon")
                } catch (e: Exception) {
                    // Not found, not a program: ProcessBuilder then says so the way the JDK always has.
                    if (!(e is WindowsProcesses.Failed && e.code in NOT_A_PROGRAM)) warn("${e.message}; started the plain way, it ends with the daemon")
                }
            }
            return plain(argv, cwd, env, log)
        }

        /** Ends [process] and those of its descendants that still know it as their ancestor. */
        fun kill(process: ProcessHandle) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }

        private fun plain(argv: List<String>, cwd: Path, env: Map<String, String>, log: Path): JobProcess {
            val builder = ProcessBuilder()
            builder.environment().putAll(env)
            builder.command(argv)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(File(if (windows) "NUL" else "/dev/null")))
            val process = builder.start()
            return JobProcess(process.toHandle(), { process.exitValue() }, JobObjects.forProcess(process.pid()), {}, survives = false)
        }

        /**
         * The job in a kill-on-close job object of its own, out of the daemon's job, with a watcher that keeps that object open and
         * records the exit code; null when the OS refuses a step (nothing then ran). Without a watcher the job still runs, but ends
         * with the daemon.
         */
        private fun owned(argv: List<String>, cwd: Path, environment: Map<String, String>, log: Path, exitFile: Path, warn: (String) -> Unit): JobProcess? {
            val tree = JobObjects.killOnClose() ?: return null
            // Only out of the daemon's own job: a job it was started in (a terminal's, an agent host's) is not its to leave.
            val breakaway = JobObjects.inOwnJob
            val child = try {
                WindowsProcesses.Stdio.appendTo(log).use { stdio ->
                    WindowsProcesses.create(argv.first(), argv.joinToString(" ", transform = DetachedStart::quote), cwd, environment, stdio, emptyList(), breakaway)
                }
            } catch (e: Exception) {
                tree.close()
                throw e
            }
            val handle = ProcessHandle.of(child.pid).orElse(null)
            if (handle == null || !tree.assign(child.process)) {
                child.abandon()
                tree.close()
                return null
            }
            ExitFile.delete(exitFile)
            val watched = try {
                watch(child, tree, exitFile, breakaway)
            } catch (e: Exception) {
                warn("no exit watcher (${e.message}): the job ends with the daemon")
                false
            }
            val exit = child.exitWatch()
            child.resume()
            return JobProcess(handle, exit::exitCode, tree, exit::close, survives = watched)
        }

        /** Starts the watcher of [child] in the job's object, holding that object and the child open; true when it runs. */
        private fun watch(child: WindowsProcesses.Created, tree: JobObjects.Tree, exitFile: Path, breakaway: Boolean): Boolean {
            val inherited = listOf(tree.handle, child.process).map(WindowsProcesses::inheritable)
            val watcher = try {
                WindowsProcesses.create("powershell", ExitFile.watcher(child.pid, inherited[1].address(), exitFile), null, null, null, inherited, breakaway)
            } finally {
                inherited.forEach(WindowsProcesses::close)
            }
            if (!tree.assign(watcher.process)) {
                watcher.abandon()
                return false
            }
            watcher.resume()
            watcher.close()
            return true
        }

        // ProcessBuilder's map: case-insensitive on Windows, like the OS (Path = PATH).
        private fun environment(env: Map<String, String>): Map<String, String> = ProcessBuilder().environment().apply { putAll(env) }

        // File or path not found, not a Win32 program.
        private val NOT_A_PROGRAM = setOf(2, 3, 193)
    }
}
