package codeloupe.write

/** Paths against a glob: a star or a question mark within a name, two stars across folders (a leading double star and slash also match no folder). */
internal object Glob {
    fun matches(glob: String, path: String): Boolean = regex(glob).matches(path)

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
