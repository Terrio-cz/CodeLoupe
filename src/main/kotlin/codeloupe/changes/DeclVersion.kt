package codeloupe.changes

import codeloupe.query.DeclRow
import codeloupe.query.OutlineQuery

/**
 * One declaration in one version of a file. [lines] are the file's lines, shared by all its declarations: a text is
 * cut out only when hashes differ or a diff is shown, so a large change set never holds many copies at once.
 */
class DeclVersion(val row: DeclRow, private val lines: List<String>) {
    val qualifiedName: String get() = if (row.container.isEmpty()) row.name else "${row.container}.${row.name}"
    val isType: Boolean get() = row.kind in OutlineQuery.TYPE_KINDS

    /** KDoc, annotations and body, line ends normalised. */
    val text: String get() = slice(row.startLine, row.endLine)

    /**
     * Same text as [other]; line ends do not count (a CRLF checkout of an LF blob is the same declaration). The stored
     * hash covers the code without its KDoc, so the KDoc lines are compared besides.
     */
    fun sameText(other: DeclVersion): Boolean =
        (row.hash == other.row.hash && kdoc() == other.kdoc()) || text == other.text

    private fun kdoc(): String = slice(row.startLine, row.declLine - 1)

    /** Only the KDoc above the declaration differs from [other]. */
    fun sameCode(other: DeclVersion): Boolean = slice(row.declLine, row.endLine) == other.slice(other.row.declLine, other.row.endLine)

    /**
     * The text outside the declarations nested in it: what a type changes by itself (an init block, a comment). Blank
     * lines do not count, so adding or removing a member with its blank line leaves it unchanged.
     */
    fun ownText(all: List<DeclVersion>): String {
        val nested = all.filter { it.row.container == qualifiedName || it.row.container.startsWith("$qualifiedName.") }
        return (row.startLine..row.endLine)
            .filter { line -> nested.none { line in it.row.startLine..it.row.endLine } }
            .map { lines.getOrElse(it - 1) { "" }.removeSuffix("\r") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun slice(from: Int, to: Int): String =
        lines.subList((from - 1).coerceIn(0, lines.size), to.coerceIn(0, lines.size)).joinToString("\n") { it.removeSuffix("\r") }

    companion object {
        /** The declarations of one file version, locals left out (they are part of their function's body). */
        fun of(rows: List<DeclRow>, content: String): List<DeclVersion> {
            val lines = content.split('\n')
            return rows.filter { !it.local }.map { DeclVersion(it, lines) }
        }
    }
}
