package codeloupe.processes

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a Gradle daemon says about itself in `<gradle user home>/daemon/<version>/daemon-<pid>.out.log`: the directory of the
 * last build it ran and whether it is busy. The operating system cannot say either: the daemon starts in its own directory,
 * works in the project's directory only while a build runs, and goes back after it. The log lines used are logged at INFO
 * (`Received command: Build{id=…, currentDir=…}`, `Marking the daemon as busy` / `as idle`), whatever the build's log level.
 */
object GradleDaemonLog {
    class State(val lastBuildDir: String?, val busy: Boolean)

    private val VERSION = Regex("""GradleDaemon\s+(\S+)""")
    private val BUILD = Regex("""Received command: Build\{id=[^,]*, currentDir=(.*?)\}""")

    /** The state of the daemon [pid] started with [commandLine], or null when no log of it is found under [homes]. */
    fun read(commandLine: String, pid: Long, homes: List<Path>): State? {
        val version = VERSION.find(commandLine)?.groupValues?.get(1) ?: return null
        val file = homes.map { it.resolve("daemon").resolve(version).resolve("daemon-$pid.out.log") }.firstOrNull { Files.isRegularFile(it) } ?: return null
        val tail = runCatching { tail(file) }.getOrNull() ?: return null
        var dir: String? = null
        var busy = false
        for (line in tail.lineSequence()) {
            BUILD.find(line)?.let { dir = it.groupValues[1] }
            when {
                "Marking the daemon as busy" in line -> busy = true
                "Marking the daemon as idle" in line -> busy = false
            }
        }
        return State(dir, busy)
    }

    /** The Gradle user homes to look in: [configured], `GRADLE_USER_HOME`, `~/.gradle`. */
    fun homes(configured: String?): List<Path> =
        listOfNotNull(configured, System.getenv("GRADLE_USER_HOME"), System.getProperty("user.home")?.let { "$it/.gradle" }).distinct().map(Path::of)

    private fun tail(file: Path): String = RandomAccessFile(file.toFile(), "r").use { f ->
        val size = f.length()
        val start = maxOf(0, size - TAIL_BYTES)
        val bytes = ByteArray((size - start).toInt())
        f.seek(start)
        f.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }

    private const val TAIL_BYTES = 512L * 1024
}
