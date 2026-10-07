package codeloupe.git

/** One line of `git ls-tree -r --long`. */
data class TreeEntry(val mode: String, val type: String, val sha: String, val size: Long, val path: String) {
    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun parse(line: String): TreeEntry {
            val tab = line.indexOf('\t')
            val (mode, type, sha, size) = line.substring(0, tab).trim().split(WHITESPACE)
            return TreeEntry(mode, type, sha, size.toLongOrNull() ?: -1, line.substring(tab + 1))
        }
    }
}
