package codeloupe.taskcode

import java.nio.file.Files
import java.nio.file.Path

/**
 * The lines of a repository's `AGENTS.md` that bear on a task: the section index with line numbers, and the bullets that mention
 * what the task touches, best first. A word found in more than a third of the bullets says nothing and counts for nothing.
 */
object AgentsNorms {
    private class Bullet(val line: Int, val heading: String, val text: String)

    fun render(root: Path, terms: NormTerms): String {
        val file = root.resolve("AGENTS.md")
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return ""
        val lines = Files.readAllLines(file)
        val bullets = ArrayList<Bullet>()
        val sections = ArrayList<String>()
        var heading = ""
        var fenced = false
        for ((i, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (fenced) continue
            val h = HEADING.find(line)
            if (h != null) {
                heading = h.groupValues[1]
                sections += "$heading ${i + 1}"
            } else if (BULLET.containsMatchIn(line)) {
                bullets += Bullet(i + 1, heading, line.trim())
            }
        }
        val index = "AGENTS.md ${lines.size} lines, sections (first line): ${sections.joinToString(" · ")}"
        val picked = pick(bullets, terms)
        if (picked.isEmpty()) return index
        return index + "\nbullets that name this task's areas (Read offset/limit for the rest):\n" +
            picked.joinToString("\n") { "  L${it.line} [${it.heading}] ${shorten(it.text)}" }
    }

    private fun pick(bullets: List<Bullet>, terms: NormTerms): List<Bullet> {
        if (bullets.isEmpty()) return emptyList()
        val patterns = terms.weights.mapValues { (word, _) -> Regex("(?<![A-Za-z0-9])${Regex.escape(word)}s?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE) }
        val common = if (bullets.size < MIN_FOR_COMMON) emptySet() else patterns.filterValues { p -> bullets.count { p.containsMatchIn(it.text) } * 3 > bullets.size }.keys
        val scored = bullets.map { b -> b to patterns.entries.filter { it.key !in common && it.value.containsMatchIn(b.text) }.sumOf { terms.weights.getValue(it.key) } }
        return scored.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }.take(MAX_BULLETS).map { it.first }.sortedBy { it.line }
    }

    private fun shorten(text: String) = if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT - 1).trimEnd() + "…"

    private val HEADING = Regex("^#{1,3} +(.+?)\\s*#*\\s*$")
    private val BULLET = Regex("^\\s*(?:[-*]|\\d+\\.) ")
    private const val MAX_BYTES = 400_000L
    private const val MAX_BULLETS = 6
    private const val MAX_TEXT = 200
    private const val MIN_SCORE = 2
    private const val MIN_FOR_COMMON = 9
}
