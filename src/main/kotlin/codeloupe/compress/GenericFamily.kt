package codeloupe.compress

/** Any output: its first lines, every error line (up to a cap, the rest counted) and its last lines; repeated lines are folded. */
object GenericFamily {
    private const val HEAD = 5
    private const val TAIL = 10
    private const val ERRORS = 20

    fun compress(text: String, cwd: String): String {
        val lines = fold(text.lines().filter { it.isNotBlank() }).map { ErrorLines.shorten(ErrorLines.relative(it, cwd)) }
        if (lines.size <= HEAD + TAIL) return lines.joinToString("\n")
        val head = lines.take(HEAD)
        val tail = lines.takeLast(TAIL)
        val middle = lines.subList(HEAD, lines.size - TAIL)
        val errors = middle.filter(ErrorLines::isError)
        return buildList {
            addAll(head)
            val omitted = middle.size - errors.take(ERRORS).size
            add("… $omitted lines omitted" + if (errors.isNotEmpty()) "; error lines kept:" else "")
            addAll(errors.take(ERRORS))
            if (errors.size > ERRORS) add("… +${errors.size - ERRORS} more error lines in the full output")
            addAll(tail)
        }.joinToString("\n")
    }

    /** Runs of the same line become one line with a count. */
    private fun fold(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            var j = i
            while (j + 1 < lines.size && lines[j + 1] == lines[i]) j++
            out += if (j > i) "${lines[i]} (×${j - i + 1})" else lines[i]
            i = j + 1
        }
        return out
    }
}
