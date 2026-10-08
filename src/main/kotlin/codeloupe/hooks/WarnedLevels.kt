package codeloupe.hooks

import java.nio.file.Files
import java.nio.file.Path

/**
 * The highest size each session has been warned about, kept in a small file so that a daemon restart does not repeat a warning.
 * A session whose context fell below what it was warned about (after `/compact`) is warned again when it grows back.
 */
class WarnedLevels(private val file: Path) {
    private val levels = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Int>) = size > MAX
    }

    init {
        runCatching {
            Files.readAllLines(file).forEach { line ->
                val at = line.lastIndexOf(' ')
                if (at > 0) line.substring(at + 1).toIntOrNull()?.let { levels[line.substring(0, at)] = it }
            }
        }
    }

    /** True when [level] is a new size for [session], which is then remembered; a lower level only lowers what is remembered. */
    @Synchronized
    fun reached(session: String, level: Int): Boolean {
        val before = levels[session] ?: 0
        if (level == before) return false
        levels[session] = level
        save()
        return level > before
    }

    private fun save() {
        runCatching { Files.writeString(file, levels.entries.joinToString("\n") { "${it.key} ${it.value}" }) }
    }

    private companion object {
        const val MAX = 200
    }
}
