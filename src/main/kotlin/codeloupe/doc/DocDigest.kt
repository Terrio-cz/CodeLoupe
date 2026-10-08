package codeloupe.doc

/** The short views of a [Doc]: a digest that fits [LIMIT] characters and names every section a reader can fetch, and the full outline. */
object DocDigest {
    const val LIMIT = 1_000
    private const val OUTLINE_LIMIT = 6_000
    private const val ERROR_SAMPLES = 3

    fun summary(doc: Doc, limit: Int = LIMIT): String {
        val head = "${name(doc)} · ${doc.lineCount} lines · ${size(doc.bytes)} · #${doc.hash}"
        val errors = errors(doc)
        val foot = "fetch: section=<handle> or L<from>-<to> · view=full · view=outline"
        val budget = (limit - head.length - errors.length - foot.length - "sections: ".length - 8).coerceAtLeast(0)
        // The shallow headings first, then as many deeper ones as fit; shown in document order.
        val shown = HashSet<String>()
        var used = 0
        for (section in doc.sections.sortedBy { it.level }) {
            val cost = entry(section).length + SEPARATOR.length
            if (used + cost > budget) break
            used += cost
            shown += section.handle
        }
        val list = doc.sections.filter { it.handle in shown }.joinToString(SEPARATOR) { entry(it) }
        val more = doc.sections.size - shown.size
        val sections = "sections: " + list + if (more > 0) " +$more" else ""
        return listOf(head, sections, errors, foot).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Every section with its handle and size, indented by heading level; long lists are cut. */
    fun outline(doc: Doc): String {
        val lines = ArrayList<String>()
        lines += "${name(doc)} · ${doc.lineCount} lines · ${size(doc.bytes)} · #${doc.hash}"
        var used = lines[0].length
        for ((i, s) in doc.sections.withIndex()) {
            val line = "  ".repeat((s.level - 1).coerceAtLeast(0)) + entry(s) + if (s.startLine > 0 || s.level > 0) "  (line ${s.startLine + 1})" else ""
            if (used + line.length > OUTLINE_LIMIT) {
                lines += "… +${doc.sections.size - i} more sections"
                break
            }
            used += line.length + 1
            lines += line
        }
        if (doc.errors.isNotEmpty()) lines += errors(doc)
        return lines.joinToString("\n")
    }

    private fun errors(doc: Doc): String {
        if (doc.errors.isEmpty()) return ""
        val shown = doc.errors.take(ERROR_SAMPLES).joinToString(SEPARATOR) { "${it.handle} \"${it.sample.take(40)}\"" }
        return "errors ${doc.errors.size}: $shown" + if (doc.errors.size > ERROR_SAMPLES) " …" else ""
    }

    /** A long path keeps its tail, so the digest stays within its limit. */
    private fun name(doc: Doc) = if (doc.id.length > 100) "…" + doc.id.takeLast(99) else doc.id

    private fun entry(s: DocSection) = "${s.handle}(${s.lines})"

    private const val SEPARATOR = " · "

    private fun size(bytes: Int) = if (bytes < 1024) "$bytes B" else "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
}
