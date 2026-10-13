package codeloupe.aot

import codeloupe.platform.NativeCalls
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Names the caches of an install the way `gradle/start/codeloupe(.bat)` do, for a JVM that no launcher started: the desktop
 * app runs the bundled jar directly (a .bat cannot run without a shell) and passes `-Dcodeloupe.aot=auto`. Same key and build
 * as the scripts, so the app and the bundle's own launcher share one set of caches and one training.
 */
internal object AotInstall {
    const val AUTO = "auto"

    /** `<home>/aot/<install key>-<build>` for the install whose `lib/` holds [classPath], or null when it is not a single jar. */
    fun prefix(home: Path, classPath: String?, windows: Boolean = NativeCalls.isWindows): Path? {
        if (classPath == null || !classPath.endsWith(".jar") || File.pathSeparator in classPath) return null
        val jar = Path.of(classPath).toAbsolutePath().normalize()
        // The shell script names the install by its physical path (`pwd -P`), the batch file by the path as given.
        val install = (if (windows) jar else runCatching { jar.toRealPath() }.getOrDefault(jar)).parent?.parent ?: return null
        val build = runCatching { build(jar) }.getOrNull() ?: return null
        return home.resolve("aot").resolve("${key(install.toString(), windows)}-$build")
    }

    /** The scripts' key: the install path with separators (all but letters and digits outside Windows) as `_`, its last 60 characters. */
    fun key(install: String, windows: Boolean): String =
        (if (windows) install.replace(Regex("[\\\\: .]"), "_") else install.replace(Regex("[^A-Za-z0-9]"), "_")).takeLast(60)

    /** The build id `build.gradle.kts` writes into the scripts: the first six bytes of the jar's SHA-256, in hex. */
    fun build(jar: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)).take(6).joinToString("") { "%02x".format(it) }
}
