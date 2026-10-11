package codeloupe.conventions

import codeloupe.lang.ImportFact

/**
 * The libraries and sibling modules the files import, by how many files import them (`io.ktor.server`, `kotlinx.serialization`).
 * The JDK, the Kotlin standard library and the module's own base package say nothing and are left out.
 */
class LibraryPart(private val tests: Boolean) : CardPart {
    override fun line(context: CardContext, width: Int): String? {
        val files = if (tests) context.tests else context.main
        val libraries = Tally()
        for (file in files) {
            file.facts.imports.mapNotNull { library(it, context.basePackage) }.distinct().forEach { libraries.add(it) }
        }
        val top = libraries.top(width, min = MIN)
        if (top.isEmpty()) return null
        return "${if (tests) "Test" else "Main"} imports (files): ${top.joinToString(", ") { "${it.key} ${it.count}" }}."
    }

    private fun library(import: ImportFact, base: String): String? {
        val fqn = import.fqn
        val segments = fqn.split('.')
        // The last segment of a single import is the imported name, not part of the package.
        val room = if (import.star) segments.size else segments.size - 1
        val key = when {
            segments[0] == "java" || segments[0] == "javax" -> return null
            segments[0] == "kotlin" && segments.getOrNull(1) != "test" -> return null
            segments[0] == "kotlinx" || segments[0] == "kotlin" -> segments.take(minOf(2, room))
            else -> segments.take(minOf(3, room))
        }.joinToString(".")
        return if (key.isEmpty() || (base.isNotEmpty() && (fqn == base || fqn.startsWith("$base.")))) null else key
    }

    private companion object {
        const val MIN = 3
    }
}
