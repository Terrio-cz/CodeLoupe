package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.ImportRow
import codeloupe.query.RefRow

/**
 * What queries derive from one base generation, an immutable file: kept for the next request instead of read again.
 * It holds the base alone, whatever overlay a request attaches; [IndexCache] masks and extends it per request. Every
 * set is bounded (rows kept, not entries) so the cache stays small in the daemon's heap.
 */
internal class BaseCache {
    val named = SharedLru<String, List<DeclRow>>(DECL_ROWS) { it.size + 1 }
    val refsNamed = SharedLru<String, List<RefRow>>(REF_ROWS) { it.size + 1 }

    /** Files by path, empty for a path the base does not hold. */
    val files = SharedLru<String, List<FileScope>>(SCOPE_ROWS) { it.sumOf(FileScope::weight) + 1 }
    val refsByLine = SharedLru<Pair<String, Int>, List<RefRow>>(LINE_ROWS) { it.size + 1 }

    private val supertypes = Once<Map<String, List<DeclRow>>>()
    private val imported = Once<Map<String, List<ImportRow>>>()

    /** Indexed types by the name of each direct supertype they list; built once per generation. */
    fun bySupertype(read: () -> List<DeclRow>): Map<String, List<DeclRow>> = supertypes.get {
        val rows = read()
        rows.size to HashMap<String, MutableList<DeclRow>>().also { map ->
            for (d in rows) d.supertypes.split(' ').filter { it.isNotEmpty() }.forEach { map.getOrPut(it) { ArrayList() } += d }
        }
    }

    /** Explicit imports by the name they introduce (the alias, else the last segment); built once per generation. */
    fun importedAs(read: () -> List<ImportRow>): Map<String, List<ImportRow>> = imported.get {
        val rows = read()
        rows.size to rows.groupBy { it.alias ?: it.fqn.substringAfterLast('.') }
    }

    /** A value built from many rows on first use and kept, unless it came from more rows than [MAP_ROWS]. */
    private class Once<T : Any> {
        private var value: T? = null

        fun get(build: () -> Pair<Int, T>): T {
            synchronized(this) { value }?.let { return it }
            val (rows, built) = build()
            if (rows <= MAP_ROWS) synchronized(this) { value = built }
            return built
        }
    }

    private companion object {
        const val DECL_ROWS = 30_000
        const val REF_ROWS = 40_000
        const val SCOPE_ROWS = 40_000
        const val LINE_ROWS = 15_000
        const val MAP_ROWS = 100_000
    }
}
