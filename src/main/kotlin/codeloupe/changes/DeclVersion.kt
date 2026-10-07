package codeloupe.changes

import codeloupe.query.DeclRow
import codeloupe.query.OutlineQuery

/** One declaration in one version of a file, with its text (KDoc and annotations included). */
data class DeclVersion(val row: DeclRow, val text: String) {
    val qualifiedName: String get() = if (row.container.isEmpty()) row.name else "${row.container}.${row.name}"
    val isType: Boolean get() = row.kind in OutlineQuery.TYPE_KINDS

    /** Line ends do not count: a CRLF checkout of an LF blob is the same declaration. */
    val normalizedText: String by lazy { text.replace("\r", "") }

    /** The text outside the declarations nested in it: what a type changes by itself (an init block, a comment). */
    fun ownText(all: List<DeclVersion>): String {
        val nested = all.filter { it.row.container == qualifiedName || it.row.container.startsWith("$qualifiedName.") }
        val lines = normalizedText.split('\n')
        return lines.filterIndexed { i, _ -> nested.none { (row.startLine + i) in it.row.startLine..it.row.endLine } }.joinToString("\n")
    }

    companion object {
        /** The declarations of one file version, locals left out (they are part of their function's body). */
        fun of(rows: List<DeclRow>, content: String): List<DeclVersion> {
            val lines = content.split('\n')
            return rows.filter { !it.local }.map { row ->
                DeclVersion(row, lines.subList((row.startLine - 1).coerceIn(0, lines.size), row.endLine.coerceIn(0, lines.size)).joinToString("\n"))
            }
        }
    }
}
