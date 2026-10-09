package codeloupe.taskcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * How task ids look in commit messages and branch names. The repository may set `taskPattern` in `.codeloupe.json`
 * (a regular expression whose whole match is the id); otherwise the mirrored tracker projects name them
 * (`TER-\d+`), and without a tracker any `ABC-12` counts, apart from a few technical names that look alike.
 */
class TaskPattern private constructor(val regex: Regex, val source: String, val hint: String) {
    /** Task ids mentioned in [text], upper case, in order of appearance, each once. */
    fun idsIn(text: String): List<String> = bounded(emptyList()) {
        regex.findAll(TimedText(text)).map { it.value.uppercase() }.filter { it.substringBefore('-') !in NOISE }.distinct().toList()
    }

    fun isId(text: String): Boolean = bounded(false) { regex.matchEntire(TimedText(text.trim())) != null && text.trim().substringBefore('-').uppercase() !in NOISE }

    // The pattern may come from the repository: one that cannot finish in time is given up for good, and finds nothing.
    @Volatile private var expired = false

    private fun <T> bounded(otherwise: T, body: () -> T): T {
        if (expired) return otherwise
        return try {
            body()
        } catch (_: TimedText.Expired) {
            expired = true
            otherwise
        }
    }

    companion object {
        private val GENERIC = Regex("(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9_]*-\\d+(?![0-9A-Za-z])")
        private val NOISE = setOf("UTF", "SHA", "ISO", "RFC", "MD", "X", "CVE", "PBKDF", "HMAC", "RS", "ES", "HS", "P", "GPT", "O", "AES", "TLS", "SSL", "IPV", "HTTP", "UUID", "BASE", "WGS", "EPSG", "SRID")

        /** The pattern of the repository whose main worktree is [mainWorktree], given the tracker [projects] it may belong to. */
        fun of(mainWorktree: Path, projects: Collection<String>): TaskPattern {
            configured(mainWorktree.resolve(".codeloupe.json"))?.let { return it }
            if (projects.isNotEmpty()) {
                val alternatives = projects.map { Regex.escape(it.uppercase()) }.distinct().sorted().joinToString("|")
                val hint = projects.map { it.uppercase() }.distinct().sorted().joinToString("|") { "$it-<n>" }
                return TaskPattern(Regex("(?<![A-Za-z0-9_])(?:$alternatives)-\\d+(?![0-9A-Za-z])", RegexOption.IGNORE_CASE), "projects:$alternatives", hint)
            }
            return TaskPattern(GENERIC, "generic", "ABC-12")
        }

        private fun configured(file: Path): TaskPattern? = runCatching {
            val text = Json.parseToJsonElement(Files.readString(file)).jsonObject["taskPattern"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
            TaskPattern(Regex("(?<![A-Za-z0-9_])(?:$text)(?![0-9A-Za-z])"), "config:$text", text)
        }.getOrNull()
    }
}
