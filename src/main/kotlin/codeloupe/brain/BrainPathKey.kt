package codeloupe.brain

/**
 * A file as a finding names it: `public-api/.../StatisticsRepository.kt:88`, a bare `AddressRoutes.kt` or a full path. The file name must
 * equal, and the directories it gives (an elided `...` stands for any) must appear in the path in that order.
 */
class BrainPathKey private constructor(val name: String, val dirs: List<String>) {
    fun matches(path: String): Boolean {
        val segments = path.replace('\\', '/').lowercase().split('/').filter { it.isNotEmpty() }
        if (segments.lastOrNull() != name) return false
        var at = 0
        for (dir in dirs) {
            while (at < segments.size - 1 && segments[at] != dir) at++
            if (at >= segments.size - 1) return false
            at++
        }
        return true
    }

    /** Module-like prefixes the directories give (`importers/ruian` for `importers/ruian/...`): what a module name may be compared with. */
    fun modulePrefixes(): List<String> {
        val upToSrc = dirs.takeWhile { it != "src" }
        return (1..upToSrc.size).map { upToSrc.take(it).joinToString("/") }
    }

    companion object {
        private val LINE = Regex(":\\d+(?:-\\d+)?$")

        fun of(raw: String): BrainPathKey? {
            val segments = raw.replace('\\', '/').replace(LINE, "").lowercase().split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            val name = segments.lastOrNull()?.takeIf { '.' in it && it != "..." } ?: return null
            return BrainPathKey(name, segments.dropLast(1).filter { it != "..." })
        }
    }
}
