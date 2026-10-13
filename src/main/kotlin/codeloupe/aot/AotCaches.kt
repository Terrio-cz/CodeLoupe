package codeloupe.aot

import java.nio.file.Files
import java.nio.file.Path

/**
 * The AOT caches of one install (JDK 25 `-XX:AOTCache`): one for the CLI, one for the daemon and one for the daemon's parse
 * worker (CL-251), kept under the CodeLoupe home beside the launcher's other caches. [prefix] is `<home>/aot/<install path>-<build>`,
 * which the launchers pass as `-Dcodeloupe.aot` (the desktop app passes `auto`, see [AotInstall]); without it (a development
 * run, a test) nothing here is used.
 *
 * A cache file is complete or absent: it is written under another name and renamed into place, and [ready] is written
 * after all of them. The JVM itself ignores a cache that does not fit it (other JDK, other jar, damaged header) and runs
 * without; [runtime] in the ready file covers the one case it cannot tell us, a runtime replaced under an unchanged jar.
 */
class AotCaches(val prefix: Path, private val runtime: String = runtimeId()) {
    val cli: Path get() = suffixed(".cli.aot")
    val daemon: Path get() = suffixed(".daemon.aot")
    val worker: Path get() = suffixed(".worker.aot")
    val ready: Path get() = suffixed(".ready")
    val lock: Path get() = suffixed(".lock")
    val failed: Path get() = suffixed(".failed")

    /** Held by the one trainer process of this install. */
    val trainer: Path get() = suffixed(".trainer")

    /** True when all caches are in place and were made by the JVM that runs now. */
    fun isReady(): Boolean = try {
        val lines = Files.readAllLines(ready)
        lines.size == 4 && lines[0] == runtime && listOf(cli, daemon, worker).withIndex().all { (i, file) -> sizeOf(file).let { it >= 0 && it == lines[i + 1].toLongOrNull() } }
    } catch (_: Exception) {
        false
    }

    /** What marks the caches complete; written last. */
    fun readyContent(): String = "$runtime\n${sizeOf(cli)}\n${sizeOf(daemon)}\n${sizeOf(worker)}\n"

    /**
     * JVM flags of a daemon start: the property that lets the daemon make the caches when they are missing, and its cache
     * when there is a usable one.
     */
    fun daemonFlags(): List<String> = listOf("-D$PROPERTY=$prefix") + if (isReady()) listOf("-XX:AOTCache=$daemon") else emptyList()

    /**
     * JVM flags of a parse worker start: its cache when there is a usable one. Asked at every start, so a worker started after the
     * trainer finished uses the cache without the daemon being restarted.
     */
    fun workerFlags(): List<String> = if (isReady()) listOf("-XX:AOTCache=$worker") else emptyList()

    internal fun suffixed(suffix: String): Path = prefix.resolveSibling(prefix.fileName.toString() + suffix)

    private fun sizeOf(file: Path): Long = try {
        Files.size(file)
    } catch (_: Exception) {
        -1
    }

    companion object {
        const val PROPERTY = "codeloupe.aot"

        /**
         * The caches the launcher named, or null when this JVM was not started by one. `auto` (the desktop app, which runs the
         * jar without a launcher) is resolved as the launcher scripts would name the caches, against [home].
         */
        fun fromProperty(home: Path? = null): AotCaches? {
            val value = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
            if (value != AotInstall.AUTO) return AotCaches(Path.of(value))
            return AotInstall.prefix(home ?: return null, System.getProperty("java.class.path"))?.let(::AotCaches)
        }

        /** What a cache depends on besides the jars: the JVM that wrote it. */
        fun runtimeId(): String = listOf(System.getProperty("java.vm.version"), System.getProperty("java.runtime.version"), System.getProperty("os.arch")).joinToString("|")
    }
}
