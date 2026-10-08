package codeloupe.processes

import codeloupe.jobs.FakeJob
import java.nio.file.Files
import java.nio.file.Path

/** Real child JVMs with a given working directory and a marker in their command line, killed with the test. */
class ChildProcesses : AutoCloseable {
    private val started = ArrayList<Process>()

    /** A JVM that sleeps (or spins for [spinMs]) in [directory], its command line carrying [marker] and then [after]. */
    fun start(directory: Path, marker: String, sleepMs: Long = 120_000, spinMs: Long = 0, after: List<String> = emptyList()): Process {
        val args = listOfNotNull(if (spinMs > 0) "spin=$spinMs" else null, "sleep=$sleepMs", marker) + after
        val process = ProcessBuilder(FakeJob.command(*args.toTypedArray())).directory(directory.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        started += process
        return process
    }

    /** A fake Gradle daemon whose log under [gradleHome] says it last built in [buildDir] and is [busy]; its own directory is [directory]. */
    fun gradleDaemon(directory: Path, gradleHome: Path, buildDir: Path?, busy: Boolean = false): Process {
        val process = start(directory, GRADLE_DAEMON_MARKER, after = listOf("9.6.0"))
        val log = gradleHome.resolve("daemon").resolve("9.6.0").resolve("daemon-${process.pid()}.out.log")
        Files.createDirectories(log.parent)
        val lines = buildList {
            add("2026-10-08T20:32:50.000+0200 [INFO] [org.gradle.launcher.daemon.server.Daemon] start() called on daemon")
            if (buildDir != null) {
                add("2026-10-08T20:32:54.240+0200 [INFO] [org.gradle.launcher.daemon.server.DefaultIncomingConnectionHandler] Received command: Build{id=58386527, currentDir=$buildDir}.")
                add("2026-10-08T20:32:54.243+0200 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as busy, address: [79eb port:60009]")
                if (!busy) add("2026-10-08T20:32:59.543+0200 [INFO] [org.gradle.launcher.daemon.server.DaemonRegistryUpdater] Marking the daemon as idle, address: [79eb port:60009]")
            }
        }
        Files.write(log, lines)
        return process
    }

    /** The process table as far as these children go: a test must not see the real builds running on the machine. */
    fun world(gradleHomes: List<Path> = emptyList()): ProcessSource = ProcessSource { SystemProcesses(gradleHomes).read().filter { info -> started.any { it.pid() == info.pid } } }

    /** Waits until the OS lists [process] with its working directory and command line: a JVM takes a moment to start. */
    fun awaitListed(process: Process, source: ProcessSource = world()): ProcessInfo {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            source.read().firstOrNull { it.pid == process.pid() && it.cwd != null && it.commandLine != null }?.let { return it }
            Thread.sleep(100)
        }
        error("pid ${process.pid()} was not listed with its directory and command line")
    }

    override fun close() = started.forEach { runCatching { it.destroyForcibly() } }
}

const val GRADLE_DAEMON_MARKER = "org.gradle.launcher.daemon.bootstrap.GradleDaemon"
const val GRADLE_CLIENT_MARKER = "org.gradle.launcher.GradleMain"
const val KOTLIN_DAEMON_MARKER = "org.jetbrains.kotlin.daemon.KotlinCompileDaemon"
