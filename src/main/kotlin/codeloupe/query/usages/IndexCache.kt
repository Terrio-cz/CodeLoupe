package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.RefRow
import codeloupe.query.View

/** Lookups one query repeats many times, read from the [view] once while they stay recently used. */
internal class IndexCache(val view: View) {
    private val byName = Lru<String, List<DeclRow>>(NAMES)
    private val refsByName = Lru<String, List<RefRow>>(NAMES)
    private val files = Lru<String, FileScope?>(FILES)
    private val refsByLine = Lru<Pair<String, Int>, List<RefRow>>(LINES)
    private val texts = Lru<String, List<String>>(TEXTS)
    private val params = Lru<String, List<Param>>(NAMES)

    /** Declarations with this name, local ones included. */
    fun named(name: String): List<DeclRow> = byName.getOrPut(name) { view.decls("d.name = :name", mapOf("name" to name)) }

    /** References with this name. */
    fun refsNamed(name: String): List<RefRow> = refsByName.getOrPut(name) { view.refs("r.name = :name", mapOf("name" to name)) }

    fun file(path: String): FileScope? = files.getOrPut(path) {
        val file = view.file(path) ?: return@getOrPut null
        val params = mapOf("path" to path)
        FileScope(path, file.packageName, view.imports("f.path = :path", params), view.decls("f.path = :path", params))
    }

    /** The reference starting at a position (a type spec's `@line:col`). */
    fun refAt(path: String, line: Int, col: Int): RefRow? =
        refsByLine.getOrPut(path to line) { view.refs("f.path = :path AND r.line = :line", mapOf("path" to path, "line" to line)) }.firstOrNull { it.col == col }

    /** Source line [n] (1-based) of a file, without its line end. */
    fun line(path: String, n: Int): String =
        texts.getOrPut(path) { view.file(path)?.content.orEmpty().split('\n') }.getOrNull(n - 1)?.removeSuffix("\r").orEmpty()

    /** Value parameters of a function or constructor. */
    fun params(d: DeclRow): List<Param> = params.getOrPut(d.params.orEmpty()) { Param.of(d) }

    fun children(d: DeclRow): List<DeclRow> = file(d.path)?.children(d).orEmpty()

    fun parent(d: DeclRow): DeclRow? = file(d.path)?.decl(d.parentId)

    /** The innermost enclosing declaration that is API, not code: what a reader navigates to. */
    fun owner(d: DeclRow?): DeclRow? = file(d?.path ?: return null)?.chain(d)?.firstOrNull { !it.local }

    private companion object {
        const val NAMES = 512
        const val FILES = 256
        const val LINES = 1024
        const val TEXTS = 32
    }
}
