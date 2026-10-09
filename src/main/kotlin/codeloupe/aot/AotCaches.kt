package codeloupe.aot

import java.nio.file.Files
import java.nio.file.Path

/**
 * The AOT caches of one install (JDK 25 `-XX:AOTCache`): one for the CLI, one for the daemon, kept under the CodeLoupe
 * home beside the launcher's other caches. [prefix] is `<home>/aot/<install path>-<build>`, which the launchers pass as
 * `-Dcodeloupe.aot`; without it (a development run, a test) nothing here is used.
 *
 * A cache file is complete or absent: it is written under another name and renamed into place, and [ready] is written
 * after both. The JVM itself ignores a cache that does not fit it (other JDK, other jar, damaged header) and runs
 * without; [runtime] in the ready file covers the one case it cannot tell us, a runtime replaced under an unchanged jar.
 */
class AotCaches(val prefix: Path, private val runtime: String = runtimeId()) {
    val cli: Path get() = suffixed(".cli.aot")
    val daemon: Path get() = suffixed(".daemon.aot")
    val ready: Path get() = suffixed(".ready")
    val lock: Path get() = suffixed(".lock")
    val failed: Path get() = suffixed(".failed")

    /** Held by the one trainer process of this install. */
    val trainer: Path get() = suffixed(".trainer")

    /** True when both caches are in place and were made by the JVM that runs now. */
    fun isReady(): Boolean = try {
        val lines = Files.readAllLines(ready)
        lines.size == 3 && lines[0] == runtime && sizeOf(cli) == lines[1].toLongOrNull() && sizeOf(daemon) == lines[2].toLongOrNull()
    } catch (_: Exception) {
        false
    }

    /** What marks the caches complete; written last. */
    fun readyContent(): String = "$runtime\n${sizeOf(cli)}\n${sizeOf(daemon)}\n"

    /**
     * JVM flags of a daemon start: the property that lets the daemon make the caches when they are missing, and its cache
     * when there is a usable one.
     */
    fun daemonFlags(): List<String> = listOf("-D$PROPERTY=$prefix") + if (isReady()) listOf("-XX:AOTCache=$daemon") else emptyList()

    internal fun suffixed(suffix: String): Path = prefix.resolveSibling(prefix.fileName.toString() + suffix)

    private fun sizeOf(file: Path): Long = try {
        Files.size(file)
    } catch (_: Exception) {
        -1
    }

    companion object {
        const val PROPERTY = "codeloupe.aot"

        /** The caches the launcher named, or null when this JVM was not started by one. */
        fun fromProperty(): AotCaches? = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { AotCaches(Path.of(it)) }

        /** What a cache depends on besides the jars: the JVM that wrote it. */
        fun runtimeId(): String = listOf(System.getProperty("java.vm.version"), System.getProperty("java.runtime.version"), System.getProperty("os.arch")).joinToString("|")
    }
}
