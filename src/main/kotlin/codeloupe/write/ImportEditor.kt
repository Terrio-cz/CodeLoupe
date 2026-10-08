package codeloupe.write

import codeloupe.lang.FileFacts

/**
 * Adds import statements to a Kotlin or Java file: none that the file already has or that need none, each at its sorted place
 * among the imports of its group (static imports in Java form a group), appended to the group when it is not sorted.
 */
internal class ImportEditor(private val text: String, private val eol: String, private val java: Boolean, private val facts: FileFacts) {
    /** An import statement of the file: [start] is the start of its line, [end] the end of its text without the line break. */
    private class Line(val key: String, val static: Boolean, val start: Int, val end: Int)

    private class Wanted(val key: String, val static: Boolean, val line: String, val fqn: String, val alias: String?)

    /** The edits adding [wanted] (`a.b.C`, `a.b.*`, Kotlin `a.b.C as D`, Java `static a.B.m`); empty when the file has them all. */
    fun add(wanted: List<String>): List<TextEdit> {
        val existing = existing()
        // Imports for a group the file has come first: a group made new goes below them.
        val fresh = wanted.map(::normalize).distinctBy { it.line }.filter { !present(it, existing) }
            .sortedWith(compareBy({ import -> existing.none { it.static == import.static } }, { it.key }))
        if (fresh.isEmpty()) return emptyList()
        if (existing.isEmpty()) return listOf(first(fresh))
        val inserted = LinkedHashMap<Int, StringBuilder>()
        val newGroups = HashSet<Boolean>()
        for (import in fresh) {
            val group = existing.filter { it.static == import.static }
            val builder: StringBuilder
            if (group.isEmpty()) {
                // A group of its own, after the last import and a blank line.
                builder = inserted.getOrPut(existing.last().end) { StringBuilder() }
                if (newGroups.add(import.static)) builder.append(eol)
                builder.append(eol).append(import.line)
            } else {
                val before = if (sorted(group)) group.firstOrNull { it.key > import.key } else null
                if (before != null) inserted.getOrPut(before.start) { StringBuilder() }.append(import.line).append(eol)
                else inserted.getOrPut(group.last().end) { StringBuilder() }.append(eol).append(import.line)
            }
        }
        return inserted.map { (at, lines) -> TextEdit(at, at, lines.toString()) }
    }

    private fun normalize(raw: String): Wanted {
        var s = raw.trim().removePrefix("import ").trim().removeSuffix(";").trim()
        val static = java && s.startsWith("static ")
        if (static) s = s.removePrefix("static ").trim()
        val alias = if (!java) Regex("""\s+as\s+(\w+)$""").find(s)?.groupValues?.get(1) else null
        val fqn = if (alias != null) s.substringBefore(" as ").trim() else s
        if (!NAME.matches(fqn)) throw WriteRefused("not an import: $raw")
        val line = "import " + (if (static) "static " else "") + fqn + (if (alias != null) " as $alias" else "") + (if (java) ";" else "")
        return Wanted(fqn + (if (alias != null) " as $alias" else ""), static, line, fqn, alias)
    }

    private fun present(import: Wanted, existing: List<Line>): Boolean {
        if (existing.any { it.static == import.static && it.key == import.key }) return true
        if (import.alias != null || import.fqn.endsWith(".*")) return false
        val owner = import.fqn.substringBeforeLast('.', "")
        return existing.any { it.static == import.static && it.key == "$owner.*" } ||
            (!import.static && owner == facts.packageName) || owner in DEFAULT_PACKAGES.getValue(java)
    }

    private fun existing(): List<Line> {
        val limit = firstDeclaration()
        return IMPORT.findAll(text).filter { it.range.first < limit }.map { m ->
            val alias = m.groupValues[3]
            Line(m.groupValues[2] + (if (alias.isNotEmpty()) " as $alias" else ""), m.groupValues[1].isNotEmpty(), m.range.first, m.range.last + 1)
        }.toList()
    }

    private fun firstDeclaration(): Int = facts.decls.firstOrNull { it.parent < 0 }?.startOffset ?: text.length

    private fun sorted(group: List<Line>) = group.map { it.key } == group.map { it.key }.sorted()

    // No import yet: after the package statement, else before the first declaration.
    private fun first(fresh: List<Wanted>): TextEdit {
        val block = fresh.joinToString(eol) { it.line }
        val packageEnd = PACKAGE.find(text)?.takeIf { it.range.first < firstDeclaration() }?.range?.last?.plus(1)
        if (packageEnd != null) return TextEdit(packageEnd, packageEnd, eol + eol + block)
        val at = firstDeclaration()
        return TextEdit(at, at, block + eol + eol)
    }

    private companion object {
        val NAME = Regex("""[A-Za-z_]\w*(\.[A-Za-z_]\w*)*(\.\*)?""")
        val IMPORT = Regex("""(?m)^[ \t]*import[ \t]+(static[ \t]+)?([\w.]+(?:\.\*)?)(?:[ \t]+as[ \t]+(\w+))?[ \t]*;?[ \t]*(?=\r?$)""")
        val PACKAGE = Regex("""(?m)^[ \t]*package[ \t]+[\w.]+[ \t]*;?""")
        val DEFAULT_PACKAGES = mapOf(
            true to setOf("java.lang"),
            false to setOf("kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons", "kotlin.io", "kotlin.ranges", "kotlin.sequences", "kotlin.text", "java.lang", "kotlin.jvm"),
        )
    }
}
