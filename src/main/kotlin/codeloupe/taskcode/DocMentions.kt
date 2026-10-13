package codeloupe.taskcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/** The markdown documents of a repository that name the touched files: the ones a change may leave stale. */
object DocMentions {
    /** One markdown document of the repository, read. */
    class Document(val path: Path, val lines: List<String>)

    /**
     * The markdown documents of [root], read; null when together they are over [READ_AHEAD_BYTES] (they are then read one at a time
     * by [render]). Reading them is most of what this costs in a worktree just made (a few hundred ms for the ~1 MB of a repository's
     * docs), and it does not depend on the task, so a caller can start it before it knows the terms.
     */
    fun readAhead(root: Path): List<Document>? {
        val docs = documents(root)
        if (docs.sumOf { Files.size(it) } > READ_AHEAD_BYTES) return null
        return docs.map { Document(it, Files.readAllLines(it)) }
    }

    /** [documents] is what [readAhead] gave for [root], if anything. */
    fun render(root: Path, terms: NormTerms, documents: List<Document>? = null): String {
        // A bare class name counts only when it is distinctive (`HttpOpenApi`, not `Application`).
        val needles = (terms.fileNames + terms.fileNames.map { it.substringBeforeLast('.') }.filter { n -> n.drop(1).any(Char::isUpperCase) }).filter { it.length >= MIN_NAME }.toSet()
        if (needles.isEmpty()) return ""
        val docs = documents?.asSequence() ?: documents(root).asSequence().map { Document(it, Files.readAllLines(it)) }
        val hits = docs.mapNotNull { doc -> hits(root, doc, needles) }.toList().sortedByDescending { it.second.size }.take(MAX_DOCS)
        return hits.joinToString("\n") { (path, lines) ->
            "  $path (${lines.size} lines): " + lines.take(PER_DOC).joinToString(" | ") { "L${it.first} ${it.second}" }
        }
    }

    private fun hits(root: Path, doc: Document, needles: Set<String>): Pair<String, List<Pair<Int, String>>>? {
        val found = doc.lines.withIndex().filter { (_, line) -> needles.any { it in line } }.map { (i, line) -> (i + 1) to snippet(line) }
        return if (found.isEmpty()) null else root.relativize(doc.path).toString().replace('\\', '/') to found
    }

    private fun documents(root: Path): List<Path> {
        fun markdown(dir: Path, depth: Int) = if (!Files.isDirectory(dir)) emptyList() else Files.walk(dir, depth).use { s ->
            s.filter { Files.isRegularFile(it) && it.extension == "md" && Files.size(it) <= MAX_BYTES && it.name !in SKIP }.toList()
        }
        return (markdown(root, 1) + markdown(root.resolve("docs"), 2)).distinct().sorted().take(MAX_FILES)
    }

    private fun snippet(line: String) = line.trim().let { if (it.length <= MAX_TEXT) it else it.take(MAX_TEXT - 1).trimEnd() + "…" }

    private val SKIP = setOf("AGENTS.md", "CLAUDE.md")
    private const val MIN_NAME = 8
    private const val MAX_DOCS = 5
    private const val PER_DOC = 2
    private const val MAX_TEXT = 130
    private const val MAX_BYTES = 600_000L
    private const val MAX_FILES = 80

    // Held at once only when small: up to 80 documents of 600 KB would not fit the daemon's heap next to everything else.
    private const val READ_AHEAD_BYTES = 4L shl 20
}
