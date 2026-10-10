package codeloupe.taskcode

import codeloupe.query.DeclRow
import codeloupe.query.View
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder
import codeloupe.repo.Registry

/**
 * Who depends on the code a task touches: per file, the other files (production first, then tests) that surely
 * reference its top-level declarations, with the number of references. Only exact hits count, so the line is a floor.
 */
class TouchedCallers(private val registry: Registry) {
    suspend fun render(root: String, paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val lines = registry.query(root, speculative = false) { view ->
            val finder = UsageFinder(view)
            paths.mapNotNull { line(view, finder, it) }
        }
        return lines.joinToString("\n")
    }

    private fun line(view: View, finder: UsageFinder, path: String): String? {
        val targets = view.decls("f.path = :path AND d.local = 0 AND d.container = ''", mapOf("path" to path), "ORDER BY start_line")
            .filterNot { "private" in it.modifiers.split(' ') }
            .sortedWith(compareBy<DeclRow> { it.kind !in TYPES }.thenByDescending { it.endLine - it.startLine })
            .take(MAX_TARGETS)
        if (targets.isEmpty()) return null
        val callers = finder.usages(targets).filter { it.label == Label.EXACT && it.ref.path != path }.groupingBy { it.ref.path }.eachCount()
        if (callers.isEmpty()) return "$path  ← no other file references ${targets.joinToString(", ") { it.name }}"
        val (tests, production) = callers.entries.sortedByDescending { it.value }.partition { isTest(it.key) }
        val shown = listOfNotNull(files(production, MAX_PRODUCTION).takeIf { it.isNotEmpty() }, files(tests, MAX_TESTS).takeIf { it.isNotEmpty() }?.let { "tests: $it" })
        return "$path  ← " + shown.joinToString(" · ")
    }

    private fun files(entries: List<Map.Entry<String, Int>>, max: Int): String =
        (entries.take(max).map { "${it.key.substringAfterLast('/')} (${it.value})" } + listOfNotNull(entries.size.takeIf { it > max }?.let { "+${it - max} more" })).joinToString(", ")

    private fun isTest(path: String) = "/test/" in path || path.endsWith("Test.kt") || path.endsWith("Tests.kt")

    private companion object {
        val TYPES = setOf("class", "interface", "object", "enum", "annotation")
        const val MAX_TARGETS = 4
        const val MAX_PRODUCTION = 6
        const val MAX_TESTS = 3
    }
}
