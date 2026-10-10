package codeloupe.taskcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/** The markdown documents of a repository that name the touched files: the ones a change may leave stale. */
object DocMentions {
    fun render(root: Path, terms: NormTerms): String {
        // A bare class name counts only when it is distinctive (`HttpOpenApi`, not `Application`).
        val needles = (terms.fileNames + terms.fileNames.map { it.substringBeforeLast('.') }.filter { n -> n.drop(1).any(Char::isUpperCase) }).filter { it.length >= MIN_NAME }.toSet()
        if (needles.isEmpty()) return ""
        val hits = documents(root).mapNotNull { doc -> hits(root, doc, needles) }.sortedByDescending { it.second.size }.take(MAX_DOCS)
        return hits.joinToString("\n") { (path, lines) ->
            "  $path (${lines.size} lines): " + lines.take(PER_DOC).joinToString(" | ") { "L${it.first} ${it.second}" }
        }
    }

    private fun hits(root: Path, doc: Path, needles: Set<String>): Pair<String, List<Pair<Int, String>>>? {
        val found = Files.readAllLines(doc).withIndex().filter { (_, line) -> needles.any { it in line } }.map { (i, line) -> (i + 1) to snippet(line) }
        return if (found.isEmpty()) null else root.relativize(doc).toString().replace('\\', '/') to found
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
}
