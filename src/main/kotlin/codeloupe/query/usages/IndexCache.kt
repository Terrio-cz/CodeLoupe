package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.ImportRow
import codeloupe.query.RefRow
import codeloupe.query.View

/**
 * Lookups one query repeats many times, read from the [view] once while they stay recently used. What comes from the
 * base index alone is kept in the [BaseCache] of its generation and shared with other requests; this cache adds the
 * view's overlay on top: its rows, and the hiding of base rows of the files it holds.
 */
internal class IndexCache(val view: View) {
    private val shared = BaseCaches.of(view.baseFile)

    /** Paths the overlay holds; every base row of one is hidden. Empty without an overlay. */
    private val masked: Set<String> by lazy { view.overlayPaths() }
    private val byName = Lru<String, List<DeclRow>>(NAMES)
    private val refsByName = Lru<String, List<RefRow>>(NAMES)
    private val files = Lru<String, FileScope?>(FILES)
    private val refsByLine = Lru<Pair<String, Int>, List<RefRow>>(LINES)
    private val texts = Lru<String, List<String>>(TEXTS)
    private val params = Lru<String, List<Param>>(NAMES)

    /** Declarations with this name, local ones included. */
    fun named(name: String): List<DeclRow> = byName.getOrPut(name) {
        val base = shared.named.getOrPut(name) { view.baseDecls("d.name = :name", mapOf("name" to name)) }
        onOverlay(base, view.overlayDecls("d.name = :name", mapOf("name" to name))) { it.path }
    }

    /** References with this name. */
    fun refsNamed(name: String): List<RefRow> = refsByName.getOrPut(name) {
        val base = shared.refsNamed.getOrPut(name) { view.baseRefs("r.name = :name", mapOf("name" to name)) }
        onOverlay(base, view.overlayRefs("r.name = :name", mapOf("name" to name))) { it.path }
    }

    fun file(path: String): FileScope? = files.getOrPut(path) {
        if (path in masked) return@getOrPut scopeOf(path)
        shared.files.getOrPut(path) { listOfNotNull(scopeOf(path)) }.firstOrNull()
    }

    private fun scopeOf(path: String): FileScope? {
        val file = view.file(path) ?: return null
        val params = mapOf("path" to path)
        return FileScope(path, file.packageName, view.imports("f.path = :path", params), view.decls("f.path = :path", params))
    }

    /** The reference starting at a position (a type spec's `@line:col`). */
    fun refAt(path: String, line: Int, col: Int): RefRow? =
        refsByLine.getOrPut(path to line) {
            val read = { view.refs("f.path = :path AND r.line = :line", mapOf("path" to path, "line" to line)) }
            if (path in masked) read() else shared.refsByLine.getOrPut(path to line, read)
        }.firstOrNull { it.col == col }

    /** Indexed types that list a type of this name as a direct supertype. */
    fun supertypedBy(name: String): List<DeclRow> {
        val base = shared.bySupertype { view.baseDecls("d.supertypes <> ''") }[name].orEmpty()
        return onOverlay(base, overlaySupertypes[name].orEmpty()) { it.path }
    }

    private val overlaySupertypes: Map<String, List<DeclRow>> by lazy {
        val map = HashMap<String, MutableList<DeclRow>>()
        for (d in view.overlayDecls("d.supertypes <> ''")) d.supertypes.split(' ').filter { it.isNotEmpty() }.forEach { map.getOrPut(it) { ArrayList() } += d }
        map
    }

    /** Explicit (non-star) imports introducing this simple or alias name. */
    fun importsAs(name: String): List<ImportRow> {
        val base = shared.importedAs { view.baseImports("i.star = 0") }[name].orEmpty()
        return onOverlay(base, overlayImports[name].orEmpty()) { it.path }
    }

    private val overlayImports: Map<String, List<ImportRow>> by lazy {
        view.overlayImports("i.star = 0").groupBy { it.alias ?: it.fqn.substringAfterLast('.') }
    }

    /** Source line [n] (1-based) of a file, without its line end. */
    fun line(path: String, n: Int): String =
        texts.getOrPut(path) { view.file(path)?.content.orEmpty().split('\n') }.getOrNull(n - 1)?.removeSuffix("\r").orEmpty()

    /** Value parameters of a function or constructor. */
    fun params(d: DeclRow): List<Param> = params.getOrPut(d.params.orEmpty()) { Param.of(d) }

    fun children(d: DeclRow): List<DeclRow> = file(d.path)?.children(d).orEmpty()

    fun parent(d: DeclRow): DeclRow? = file(d.path)?.decl(d.parentId)

    /** The innermost enclosing declaration that is API, not code: what a reader navigates to. */
    fun owner(d: DeclRow?): DeclRow? = file(d?.path ?: return null)?.chain(d)?.firstOrNull { !it.local }

    // The overlay's rows first, then the base's that no overlay file hides: the order `View` itself returns.
    private fun <T> onOverlay(base: List<T>, overlay: List<T>, path: (T) -> String): List<T> =
        if (masked.isEmpty()) base else overlay + base.filter { path(it) !in masked }

    private companion object {
        const val NAMES = 512
        const val FILES = 256
        const val LINES = 1024
        const val TEXTS = 32
    }
}
