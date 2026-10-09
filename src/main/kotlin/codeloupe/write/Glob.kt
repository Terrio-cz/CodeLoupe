package codeloupe.write

/**
 * Paths against a glob: a star or a question mark within a name, two stars across folders (a leading double star and slash also match no folder).
 * A glob comes from a file the repository owns, so its cost is bounded: a run of stars counts as one, and one too long or with too many wildcards
 * is not judged at all but counted as a match, so that a deny rule never gets weaker by being complicated.
 */
internal object Glob {
    private const val MAX_LENGTH = 200
    private const val MAX_WILDCARDS = 8
    private val FOLDER_RUN = Regex("""(?:\*\*/)+""")
    private val STAR_RUN = Regex("""\*{2,}""")

    fun matches(glob: String, path: String): Boolean {
        val simple = STAR_RUN.replace(FOLDER_RUN.replace(glob, "**/"), "**")
        if (simple.length > MAX_LENGTH || simple.count { it == '*' || it == '?' } > MAX_WILDCARDS) return true
        return regex(simple).matches(path)
    }

    private fun regex(glob: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 2 }
                c == '*' && glob.startsWith("**", i) -> { out.append(".*"); i += 1 }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }
}
