package codeloupe.triage

/**
 * Compiler errors of a build summary grouped by the declaration they are in: one line says which declaration (with the
 * `symbol` call and hash that read and edit it), the errors under it share it, and a message that repeats in several
 * declarations (a cascade from one missing symbol) is said once. Errors whose place the index cannot name stay as printed.
 */
object ErrorTriage {
    private const val CASCADE_FROM = 3
    private const val GROUPS = 12

    fun apply(lines: List<String>, locator: DeclLocator): List<String> {
        val located = ArrayList<Pair<Int, Pair<BuildError, DeclPointer>>>()
        lines.forEachIndexed { i, line ->
            val error = BuildError.parse(line) ?: return@forEachIndexed
            locator.at(error.path, error.line)?.let { located += i to (error to it) }
        }
        if (located.isEmpty()) return lines
        val replaced = located.map { it.first }.toSet()
        val block = render(located.map { it.second })
        val first = replaced.min()
        val out = ArrayList<String>()
        lines.forEachIndexed { i, line ->
            if (i == first) out += block
            if (i !in replaced) out += line
        }
        return out
    }

    private fun render(errors: List<Pair<BuildError, DeclPointer>>): List<String> {
        val byMessage = errors.groupBy { it.first.message }
        val cascades = byMessage.filter { (_, hits) -> hits.map { it.second.key }.distinct().size >= CASCADE_FROM }
        val out = ArrayList<String>()
        for ((message, hits) in cascades) {
            val first = hits.first()
            val where = hits.map { it.second.key }.distinct().size
            out += "same error ×${hits.size} in $where declarations: $message"
            out += "  first at ${first.second.located()}  · ${first.second.next()}  (${first.first.line}${first.first.column?.let { ":$it" }.orEmpty()})"
        }
        val groups = errors.filter { it.first.message !in cascades }.groupBy { it.second.key }
        for ((_, hits) in groups.entries.take(GROUPS)) {
            val pointer = hits.first().second
            out += "${pointer.located()}  · ${pointer.next()}"
            for ((message, same) in hits.groupBy { it.first.message }) {
                val places = same.joinToString(", ") { (e, _) -> "${e.line}${e.column?.let { ":$it" }.orEmpty()}" }
                out += "  $places  $message" + if (same.size > 1) "  ×${same.size}" else ""
            }
        }
        if (groups.size > GROUPS) {
            val rest = groups.entries.drop(GROUPS)
            out += "… +${rest.size} more declarations with ${rest.sumOf { it.value.size }} errors in the full output"
        }
        return out
    }
}
