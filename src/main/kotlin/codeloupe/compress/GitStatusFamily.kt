package codeloupe.compress

/** `git status` (long, `-s` or `--porcelain`): the branch, then a count and the first names per kind of change. */
object GitStatusFamily : Family {
    private val COMMAND = Regex("""(^|[\s/\\])git(\.exe)?\s+(-\S+\s+(\S+\s+)?)*status\b""")
    private val ENTRY = Regex("""^\t(modified|new file|deleted|renamed|copied|typechange|both modified|both added|deleted by \w+|added by \w+):\s+(.+)$""")
    private val SHORT = Regex("""^([ MADRCU?!])([ MADRCU?!]) (.+)$""")
    private const val NAMES = 6

    override fun matches(line: String) = COMMAND.containsMatchIn(line)

    override fun compress(text: String, cwd: String): String {
        val staged = ArrayList<String>()
        val unstaged = ArrayList<String>()
        val untracked = ArrayList<String>()
        val notes = ArrayList<String>()
        var section: MutableList<String>? = null
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> notes += line.removePrefix("## ")
                line.startsWith("On branch ") -> notes += line.removePrefix("On branch ")
                line.startsWith("HEAD detached") -> notes += line
                line.startsWith("Your branch") || line.startsWith("nothing to commit") || line.startsWith("no changes added") -> notes += line
                line.startsWith("Changes to be committed") -> section = staged
                line.startsWith("Changes not staged") || line.startsWith("Unmerged paths") -> section = unstaged
                line.startsWith("Untracked files") -> section = untracked
                ENTRY.matches(line) -> ENTRY.find(line)!!.let { m -> section?.add("${m.groupValues[1]} ${m.groupValues[2]}") }
                line.startsWith("\t") && section === untracked -> untracked += line.trim()
                SHORT.matches(line) -> SHORT.find(line)!!.let { m -> short(m.groupValues[1], m.groupValues[2], m.groupValues[3], staged, unstaged, untracked) }
            }
        }
        if (staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && notes.none { it.startsWith("nothing to commit") }) error("not a status")
        val clean = notes.any { it.startsWith("nothing to commit") } && staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()
        return buildList {
            add(notes.filterNot { it.startsWith("nothing to commit") || it.startsWith("no changes added") }.joinToString(" · "))
            if (clean) add("clean")
            group("staged", staged)?.let(::add)
            group("unstaged", unstaged)?.let(::add)
            group("untracked", untracked)?.let(::add)
        }.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun short(x: String, y: String, path: String, staged: MutableList<String>, unstaged: MutableList<String>, untracked: MutableList<String>) {
        if (x == "?" && y == "?") untracked += path
        else {
            if (x != " ") staged += "${word(x)} $path"
            if (y != " ") unstaged += "${word(y)} $path"
        }
    }

    private fun word(code: String) = when (code) {
        "M" -> "modified"
        "A" -> "new file"
        "D" -> "deleted"
        "R" -> "renamed"
        "C" -> "copied"
        "U" -> "unmerged"
        else -> code
    }

    private fun group(name: String, items: List<String>): String? =
        if (items.isEmpty()) null else "$name ${items.size}: " + items.take(NAMES).joinToString(", ") + if (items.size > NAMES) ", … +${items.size - NAMES}" else ""
}
