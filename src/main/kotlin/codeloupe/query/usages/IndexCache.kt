package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.RefRow
import codeloupe.query.View

/** Lookups one query repeats many times, each read from the [view] once. */
internal class IndexCache(val view: View) {
    private val byName = HashMap<String, List<DeclRow>>()
    private val files = HashMap<String, FileScope?>()

    /** Declarations with this name, local ones included. */
    fun named(name: String): List<DeclRow> = byName.getOrPut(name) { view.decls("d.name = :name", mapOf("name" to name)) }

    fun file(path: String): FileScope? = files.getOrPut(path) {
        view.file(path)?.let { f -> FileScope(path, f.packageName, view.imports("f.path = :path", mapOf("path" to path)), view.decls("f.path = :path", mapOf("path" to path)), f.content) }
    }

    /** The reference starting at a position (a type spec's `@line:col`). */
    fun refAt(path: String, line: Int, col: Int): RefRow? =
        view.refs("f.path = :path AND r.line = :line AND r.col = :col", mapOf("path" to path, "line" to line, "col" to col)).firstOrNull()

    fun children(d: DeclRow): List<DeclRow> = file(d.path)?.children(d).orEmpty()

    fun parent(d: DeclRow): DeclRow? = file(d.path)?.decl(d.parentId)

    /** The innermost enclosing declaration that is API, not code: what a reader navigates to. */
    fun owner(d: DeclRow?): DeclRow? = file(d?.path ?: return null)?.chain(d)?.firstOrNull { !it.local }
}
