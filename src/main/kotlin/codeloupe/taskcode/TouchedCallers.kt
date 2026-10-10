package codeloupe.taskcode

import codeloupe.query.DeclRow
import codeloupe.query.View
import codeloupe.query.usages.Label
import codeloupe.query.usages.UsageFinder
import codeloupe.repo.Registry

/**
 * Who depends on the code a task touches: per file, the other files (production first, then tests) that surely
 * reference its top-level declarations, with the number of references; then, for the member functions the task is
 * predicted to change, the files that call them (a route that passes a request on is a caller of a service method, not
 * of its class). Only exact hits count, so the lines are a floor.
 */
class TouchedCallers(private val registry: Registry) {
    /** [members] are predicted declarations as `path:from-to`. */
    suspend fun render(root: String, paths: List<String>, members: List<String> = emptyList()): String {
        if (paths.isEmpty()) return ""
        val lines = registry.query(root, speculative = false) { view ->
            val finder = UsageFinder(view)
            paths.mapNotNull { line(view, finder, it) } + memberLines(view, finder, members)
        }
        return lines.joinToString("\n")
    }

    private fun memberLines(view: View, finder: UsageFinder, members: List<String>): List<String> {
        val targets = members.mapNotNull { target ->
            val path = target.substringBefore(':')
            val start = target.substringAfter(':').substringBefore('-').toIntOrNull() ?: return@mapNotNull null
            view.decls("f.path = :path AND d.start_line = :start AND d.local = 0 AND d.container != '' AND d.kind = 'fun'", mapOf("path" to path, "start" to start)).firstOrNull()
        }.filterNot { "private" in it.modifiers.split(' ') }.distinctBy { it.id }.take(MAX_MEMBERS)
        return targets.mapNotNull { decl ->
            val callers = finder.usages(listOf(decl)).filter { it.label == Label.EXACT && it.ref.path != decl.path }.groupingBy { it.ref.path }.eachCount()
            if (callers.isEmpty()) return@mapNotNull null
            val (tests, production) = callers.entries.sortedByDescending { it.value }.partition { isTest(it.key) }
            val shown = listOfNotNull(files(production, MAX_PRODUCTION).takeIf { it.isNotEmpty() }, files(tests, MAX_TESTS).takeIf { it.isNotEmpty() }?.let { "tests: $it" })
            "${decl.container}.${decl.name}()  ← " + shown.joinToString(" · ")
        }
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
        const val MAX_MEMBERS = 4
        const val MAX_PRODUCTION = 6
        const val MAX_TESTS = 3
    }
}
