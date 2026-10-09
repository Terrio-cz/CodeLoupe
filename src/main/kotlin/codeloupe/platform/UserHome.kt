package codeloupe.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * The home directory the JVM and the shell scripts must agree on. On Linux and macOS the JVM takes `user.home` from the passwd entry,
 * while the hook script, the launcher and git read `$HOME`; with `HOME` overridden (an isolated profile, `sudo -E`) the hook then looked for
 * `daemon.json` where the daemon never wrote it and every hook did nothing. `$HOME` wins when it names a directory.
 */
object UserHome {
    /** Makes `user.home` follow `$HOME`; called first thing in `main`. */
    fun adopt() {
        System.setProperty("user.home", resolve(System.getenv(), System.getProperty("user.home"), NativeCalls.isWindows))
    }

    internal fun resolve(env: Map<String, String>, jvmHome: String, windows: Boolean): String {
        if (windows) return jvmHome
        val home = env["HOME"]?.takeIf { it.isNotEmpty() } ?: return jvmHome
        return if (runCatching { Path.of(home).let { it.isAbsolute && Files.isDirectory(it) } }.getOrDefault(false)) home else jvmHome
    }
}
