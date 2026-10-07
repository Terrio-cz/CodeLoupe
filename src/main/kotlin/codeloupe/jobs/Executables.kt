package codeloupe.jobs

import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows finds only `.exe` programs by bare name; agents write `./gradlew` or `npm`. Resolves such a name the way
 * Bash does, to the `.exe`/`.bat`/`.cmd` behind it: a bare name on PATH only (never the job's directory, which Bash
 * does not search either), a path relative to the job's directory. Elsewhere unchanged.
 */
object Executables {
    fun resolve(program: String, cwd: Path, env: Map<String, String>, windows: Boolean): String {
        if (!windows) return program
        val extensions = (env["PATHEXT"] ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotBlank() }
        val name = program.replace('/', '\\')
        val dirs = if ('\\' in name || ':' in name) {
            listOf(cwd)
        } else {
            (env["PATH"] ?: env["Path"] ?: "").split(';').filter { it.isNotBlank() }.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        }
        for (dir in dirs) {
            val base = runCatching { dir.resolve(name).normalize() }.getOrNull() ?: continue
            if (extensions.any { base.toString().endsWith(it, ignoreCase = true) } && Files.isRegularFile(base)) return base.toString()
            for (ext in extensions) {
                val candidate = Path.of(base.toString() + ext.lowercase())
                if (Files.isRegularFile(candidate)) return candidate.toString()
            }
        }
        return program
    }

    /** True for a batch file: Windows runs it through cmd.exe, which parses its arguments again. */
    fun isBatch(path: String): Boolean = path.endsWith(".bat", ignoreCase = true) || path.endsWith(".cmd", ignoreCase = true)
}
