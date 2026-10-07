package codeloupe.jobs

import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows finds only `.exe` programs by bare name; agents write `./gradlew` or `npm`. Resolves such a name to the
 * `.bat`/`.cmd`/`.exe` next to it (in the job's directory, or on PATH) the way a shell would. Elsewhere unchanged.
 */
object Executables {
    fun resolve(program: String, cwd: Path, env: Map<String, String>, windows: Boolean): String {
        if (!windows) return program
        val extensions = (env["PATHEXT"] ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotBlank() }
        val name = program.replace('/', '\\')
        val dirs = if ('\\' in name || ':' in name) listOf(cwd) else listOf(cwd) + (env["PATH"] ?: env["Path"] ?: "").split(';').filter { it.isNotBlank() }.map { Path.of(it) }
        for (dir in dirs) {
            val base = runCatching { dir.resolve(name) }.getOrNull() ?: continue
            if (extensions.any { base.toString().endsWith(it, ignoreCase = true) } && Files.isRegularFile(base)) return base.toString()
            for (ext in extensions) {
                val candidate = Path.of(base.toString() + ext.lowercase())
                if (Files.isRegularFile(candidate)) return candidate.toString()
            }
        }
        return program
    }
}
