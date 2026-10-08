package codeloupe.git

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The exclude files outside the repository that git reads: `core.excludesFile`, or its default
 * `$XDG_CONFIG_HOME/git/ignore`. Worked out from the config files like git does, without a git process.
 */
object GlobalExcludes {
    private val SECTION = Regex("""^\[\s*([A-Za-z0-9.-]+)(?:\s+"[^"]*")?\s*]\s*(.*)$""")
    private val EXCLUDES = Regex("""^excludesfile\s*=\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** The file git reads global ignore rules from for the repository at [commonDir]; the last config that sets it wins. */
    fun file(commonDir: Path): Path {
        val home = home()
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home?.resolve(".config")
        val global = System.getenv("GIT_CONFIG_GLOBAL")?.takeIf { it.isNotBlank() }?.let { listOf(Path.of(it)) }
            ?: listOfNotNull(xdg?.resolve("git/config"), home?.resolve(".gitconfig"))
        var configured: String? = null
        for (config in global + listOf(commonDir.resolve("config"))) configured = excludesFile(config) ?: configured
        configured?.let { return expand(it, home) }
        return (xdg ?: Path.of("")).resolve("git/ignore")
    }

    private fun excludesFile(config: Path): String? = runCatching {
        if (!config.isRegularFile()) return null
        var core = false
        var found: String? = null
        for (raw in config.readText().lineSequence()) {
            val line = raw.trim()
            val section = SECTION.matchEntire(line)
            if (section != null) {
                core = section.groupValues[1].equals("core", ignoreCase = true)
                // `[core] excludesFile = x` on one line.
                if (core) EXCLUDES.matchEntire(section.groupValues[2].trim())?.let { found = value(it.groupValues[1]) }
            } else if (core) {
                EXCLUDES.matchEntire(line)?.let { found = value(it.groupValues[1]) }
            }
        }
        found
    }.getOrNull()

    private fun value(raw: String): String? {
        val text = raw.trim()
        if (text.startsWith('"')) return text.drop(1).substringBefore('"').takeIf { it.isNotEmpty() }
        return text.substringBefore(" #").substringBefore(" ;").trim().takeIf { it.isNotEmpty() }
    }

    private fun expand(path: String, home: Path?): Path =
        if (home != null && (path == "~" || path.startsWith("~/"))) home.resolve(path.removePrefix("~").removePrefix("/")) else Path.of(path)

    private fun home(): Path? = (System.getenv("HOME") ?: System.getProperty("user.home"))?.takeIf { it.isNotBlank() }?.let(Path::of)
}
