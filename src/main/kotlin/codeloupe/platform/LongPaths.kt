package codeloupe.platform

import java.nio.file.Path

/**
 * Paths past Windows' MAX_PATH (260) under a deep CodeLoupe home (CL-253). Java's own file calls take them, but SQLite's
 * Windows file layer and the working directory of a new process do not; elsewhere both are returned as they are.
 */
object LongPaths {
    /** Longest SQLite database path opened as it is: room for the `-journal` file SQLite names beside it. */
    private const val SQLITE_PLAIN_MAX = 250

    /**
     * Longest working directory a process is started in. CreateProcess takes up to 258 characters, but a JVM in a directory past
     * ~244 resolves a relative name such as `NUL` (`Redirect.DISCARD`) to an extended path, which is no device.
     */
    const val PROCESS_DIR_MAX = 240

    /** The JDBC URL of the SQLite database [file]; see [sqliteName]. */
    fun sqliteUrl(file: Path, windows: Boolean = NativeCalls.isWindows): String = "jdbc:sqlite:" + sqliteName(file, windows)

    /**
     * How SQLite is given [file] (also in `ATTACH`, on a connection opened with URI names, see [codeloupe.index.Store.open]). A long
     * path on Windows goes as a URI of its extended-length form (`\\?\C:\...`), which SQLite passes on to Windows as it is;
     * percent-encoded, as `?` would start the URI's query.
     */
    fun sqliteName(file: Path, windows: Boolean = NativeCalls.isWindows): String {
        val path = file.toAbsolutePath().normalize().toString()
        if (!windows || path.length <= SQLITE_PLAIN_MAX) return path
        val extended = if (path.startsWith("\\\\")) "\\\\?\\UNC\\" + path.removePrefix("\\\\") else "\\\\?\\$path"
        return "file:" + extended.replace("%", "%25").replace("?", "%3F").replace("#", "%23").replace("\\", "%5C")
    }

    /** True when a process can be started in [dir]. */
    fun fitsProcessDir(dir: Path, windows: Boolean = NativeCalls.isWindows): Boolean =
        !windows || dir.toAbsolutePath().toString().length <= PROCESS_DIR_MAX

    /**
     * [dir], or where a process is started instead when Windows cannot start one in [dir]: the temporary directory, else the root of
     * [dir]'s drive. For processes that take every path they need as an argument.
     */
    fun processDir(dir: Path, windows: Boolean = NativeCalls.isWindows): Path {
        if (fitsProcessDir(dir, windows)) return dir
        val temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
        return if (fitsProcessDir(temp, windows)) temp else dir.toAbsolutePath().root
    }
}
