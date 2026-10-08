package codeloupe.doc

/**
 * A named text split into sections a reader can fetch by [DocSection.handle], with a content hash to tell versions apart.
 * Markdown headings (and `=== title ===` banners) make the sections; an unstructured text, such as the output of a
 * build, is cut into line windows (`L1-60`). [errors] are windows around the lines that look like failures.
 */
class Doc(val id: String, val sections: List<DocSection>, val errors: List<Window>, val text: String) {
    /** A line range `from..to` (1-based, inclusive) with the first line that put it in the index. */
    class Window(val from: Int, val to: Int, val sample: String) {
        val handle: String get() = "L$from-$to"
    }

    val lineCount: Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith("\n")) 0 else 1
    val bytes: Int get() = text.length

    /** Over every section's own text: equal hashes mean the same document. */
    val hash: String by lazy { DocHash.of(sections.joinToString("\n") { "${it.handle}:${it.ownHash}" }) }

    /** Sections by handle, else by heading prefix (case-insensitive); an empty list when [name] names none. */
    fun find(name: String): List<DocSection> {
        val wanted = name.trim().lowercase()
        return sections.filter { it.handle == wanted }.ifEmpty { sections.filter { it.title.lowercase().startsWith(wanted) || it.handle.startsWith(wanted) } }
    }

    companion object {
        const val WINDOW_LINES = 60
        private const val MAX_ERRORS = 20
        private val HEADING = Regex("^(#{1,6}) +(.+?)\\s*#*\\s*$")
        private val BANNER = Regex("^(?:={3,}|-{3,}) +(.+?) +(?:={3,}|-{3,})$")
        private val FAILURE = Regex("\\b(error|exception|fail(ed|ure|s)?|fatal|panic|traceback)\\b|\\bERR!", RegexOption.IGNORE_CASE)

        /** A document built from sections that are already separate pieces (a composed answer), in order. */
        fun of(id: String, pieces: List<Triple<String, String, String>>): Doc {
            val sections = pieces.mapIndexed { i, (handle, title, text) -> DocSection(handle, title, 1, i, text, text) }
            return Doc(id, sections, emptyList(), sections.joinToString("\n\n") { it.own })
        }

        fun parse(id: String, text: String): Doc {
            val lines = text.replace("\r\n", "\n").lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            val headings = headings(lines)
            val sections = if (headings.size >= 2) byHeadings(lines, headings) else windows(lines)
            // A markdown document talks about errors in prose; the index is for output, which has none or only banners.
            return Doc(id, sections, if (headings.count { it.markdown } >= 2) emptyList() else errors(lines), lines.joinToString("\n"))
        }

        private class Heading(val line: Int, val level: Int, val title: String, val markdown: Boolean = true)

        private fun headings(lines: List<String>): List<Heading> {
            var fenced = false
            val found = ArrayList<Heading>()
            for ((i, line) in lines.withIndex()) {
                if (line.trimStart().startsWith("```")) fenced = !fenced
                if (fenced) continue
                val heading = HEADING.find(line)?.let { Heading(i, it.groupValues[1].length, it.groupValues[2]) }
                    ?: BANNER.find(line)?.let { Heading(i, 1, it.groupValues[1], markdown = false) }
                if (heading != null) found += heading
            }
            return found
        }

        private fun byHeadings(lines: List<String>, headings: List<Heading>): List<DocSection> {
            val used = HashSet<String>()
            val sections = ArrayList<DocSection>()
            if (headings.first().line > 0 && lines.take(headings.first().line).any { it.isNotBlank() }) {
                val intro = lines.take(headings.first().line).joinToString("\n").trim('\n')
                used += "intro"
                sections += DocSection("intro", "intro", 0, 0, intro, intro)
            }
            for ((i, h) in headings.withIndex()) {
                val ownEnd = headings.getOrNull(i + 1)?.line ?: lines.size
                val bodyEnd = headings.drop(i + 1).firstOrNull { it.level <= h.level }?.line ?: lines.size
                sections += DocSection(unique(slug(h.title), used), h.title, h.level, h.line, join(lines, h.line, ownEnd), join(lines, h.line, bodyEnd))
            }
            return sections
        }

        private fun windows(lines: List<String>): List<DocSection> {
            if (lines.size <= WINDOW_LINES) return listOf(DocSection("text", "text", 1, 0, lines.joinToString("\n"), lines.joinToString("\n")))
            return lines.indices.step(WINDOW_LINES).map { from ->
                val to = minOf(lines.size, from + WINDOW_LINES)
                val text = join(lines, from, to)
                DocSection("L${from + 1}-$to", "lines ${from + 1}-$to", 1, from, text, text)
            }
        }

        /** Each failure line with a little context; windows that touch are merged, at most [MAX_ERRORS]. */
        private fun errors(lines: List<String>): List<Window> {
            val windows = ArrayList<Window>()
            for ((i, line) in lines.withIndex()) {
                if (windows.size >= MAX_ERRORS || !FAILURE.containsMatchIn(line)) continue
                val n = i + 1
                val last = windows.lastOrNull()
                if (last != null && n - 2 <= last.to) windows[windows.lastIndex] = Window(last.from, minOf(lines.size, n + 4), last.sample)
                else windows += Window(maxOf(1, n - 2), minOf(lines.size, n + 4), line.trim().take(SAMPLE))
            }
            return windows
        }

        fun slug(title: String): String = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).trim('-').ifEmpty { "section" }

        private fun unique(slug: String, used: MutableSet<String>): String {
            var handle = slug
            var n = 2
            while (!used.add(handle)) handle = "$slug-${n++}"
            return handle
        }

        private fun join(lines: List<String>, from: Int, to: Int) = lines.subList(from, to).joinToString("\n").trimEnd('\n', ' ')

        private const val SAMPLE = 70
    }
}
