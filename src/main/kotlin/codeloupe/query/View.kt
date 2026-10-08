package codeloupe.query

import codeloupe.index.Store
import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import java.nio.file.Path
import java.sql.PreparedStatement

/**
 * Read view over a base index, optionally with a worktree overlay attached as `ov`. Every query goes through
 * here so overlay masking (a worktree's copy of a file hides the base copy) lives in one place.
 */
class View(val baseFile: Path, val overlayFile: Path? = null) : AutoCloseable {
    private val openedAt = System.nanoTime()
    private val db = Store.open(baseFile, readOnly = true)
    private val overlay = overlayFile != null
    // A pooled view serves many queries: only the statements used most recently stay compiled.
    private val statements = object : LinkedHashMap<String, Pair<PreparedStatement, NamedSql>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Pair<PreparedStatement, NamedSql>>): Boolean =
            (size > MAX_STATEMENTS).also { if (it) eldest.value.first.close() }
    }

    init {
        if (overlayFile != null) {
            try {
                db.createStatement().use { it.execute("ATTACH DATABASE '${overlayFile.toString().replace("'", "''")}' AS ov") }
                // One read transaction: every statement sees the same version of an overlay that a refresh may be rewriting.
                db.autoCommit = false
            } catch (e: Exception) {
                // Left open, the base connection would keep Windows from ever deleting the base.
                db.close()
                throw e
            }
        }
        Timings.add(TimedPart.OPEN, System.nanoTime() - openedAt)
    }

    /**
     * Readies a pooled view for its next query: ends the read transaction, so that query sees the overlay as it is
     * then and a refresh's write-ahead log is not held back, and frees the page cache (an idle view holds ~4 MB
     * otherwise; its next query reads the pages back from the OS cache). False when the connection is unusable.
     */
    fun reset(): Boolean = runCatching {
        if (overlay) db.rollback()
        db.createStatement().use { it.execute("PRAGMA shrink_memory") }
    }.isSuccess

    /** True when the view reads [file], as its base or its overlay. */
    fun reads(file: Path): Boolean = file == baseFile || file == overlayFile

    /** The base commit the attached overlay was made against; null without an overlay. */
    fun overlayBase(): String? =
        if (overlay) query("SELECT value FROM ov.meta WHERE key = 'base'", emptyMap()) { it.getString(1) }.firstOrNull() else null

    /**
     * Declarations matching [where] (written against aliases `d`/`f`, with `:params`; `{db}` names the database of the
     * row, for a subquery) over base and overlay.
     */
    fun decls(where: String, params: Map<String, Any?> = emptyMap(), tail: String = ""): List<DeclRow> {
        val base = "SELECT ${DeclRow.COLUMNS}, 'base' AS src FROM main.decls d JOIN main.files f ON f.id = d.file_id WHERE (${where.replace("{db}", "main")})"
        val sql = if (overlay) {
            """SELECT * FROM (SELECT ${DeclRow.COLUMNS}, 'ov' AS src FROM ov.decls d JOIN ov.files f ON f.id = d.file_id WHERE (${where.replace("{db}", "ov")})
           UNION ALL $base AND f.path NOT IN (SELECT path FROM ov.files)) $tail"""
        } else {
            "SELECT * FROM ($base) $tail"
        }
        return query(sql, params, DeclRow::of)
    }

    /** References matching [where] (aliases `r`/`f`), masked like [decls]. */
    fun refs(where: String, params: Map<String, Any?> = emptyMap()): List<RefRow> {
        val base = "SELECT ${RefRow.COLUMNS}, 'base' AS src FROM main.refs r JOIN main.files f ON f.id = r.file_id WHERE ($where)"
        val sql = if (overlay) {
            """SELECT ${RefRow.COLUMNS}, 'ov' AS src FROM ov.refs r JOIN ov.files f ON f.id = r.file_id WHERE ($where)
           UNION ALL $base AND f.path NOT IN (SELECT path FROM ov.files)"""
        } else {
            base
        }
        return query(sql, params, RefRow::of)
    }

    /** Paths the overlay holds, tombstones included: each hides the base copy of its file. Empty without an overlay. */
    fun overlayPaths(): Set<String> =
        if (overlay) query("SELECT path FROM ov.files", emptyMap()) { it.getString(1) }.toSet() else emptySet()

    /** Declarations of the base alone, as [decls] without the masking: what every view of this base shares. */
    fun baseDecls(where: String, params: Map<String, Any?> = emptyMap()): List<DeclRow> = query(
        "SELECT ${DeclRow.COLUMNS}, 'base' AS src FROM main.decls d JOIN main.files f ON f.id = d.file_id WHERE (${where.replace("{db}", "main")})",
        params, DeclRow::of,
    )

    /** Declarations of the overlay alone; none without one. */
    fun overlayDecls(where: String, params: Map<String, Any?> = emptyMap()): List<DeclRow> = if (!overlay) emptyList() else query(
        "SELECT ${DeclRow.COLUMNS}, 'ov' AS src FROM ov.decls d JOIN ov.files f ON f.id = d.file_id WHERE (${where.replace("{db}", "ov")})",
        params, DeclRow::of,
    )

    /** References of the base alone (see [baseDecls]). */
    fun baseRefs(where: String, params: Map<String, Any?> = emptyMap()): List<RefRow> = query(
        "SELECT ${RefRow.COLUMNS}, 'base' AS src FROM main.refs r JOIN main.files f ON f.id = r.file_id WHERE ($where)", params, RefRow::of,
    )

    /** References of the overlay alone; none without one. */
    fun overlayRefs(where: String, params: Map<String, Any?> = emptyMap()): List<RefRow> = if (!overlay) emptyList() else query(
        "SELECT ${RefRow.COLUMNS}, 'ov' AS src FROM ov.refs r JOIN ov.files f ON f.id = r.file_id WHERE ($where)", params, RefRow::of,
    )

    /** Imports of the base alone (see [baseDecls]). */
    fun baseImports(where: String, params: Map<String, Any?> = emptyMap()): List<ImportRow> = query(
        "SELECT i.fqn, i.alias, i.star, f.path FROM main.imports i JOIN main.files f ON f.id = i.file_id WHERE ($where)", params, ImportRow::of,
    )

    /** Imports of the overlay alone; none without one. */
    fun overlayImports(where: String, params: Map<String, Any?> = emptyMap()): List<ImportRow> = if (!overlay) emptyList() else query(
        "SELECT i.fqn, i.alias, i.star, f.path FROM ov.imports i JOIN ov.files f ON f.id = i.file_id WHERE ($where)", params, ImportRow::of,
    )

    /** How many references are named [name], counted up to [cap]: a size check that loads no rows. */
    fun refCount(name: String, cap: Int): Int {
        val base = "SELECT 1 FROM main.refs r JOIN main.files f ON f.id = r.file_id WHERE r.name = :name"
        val rows = if (overlay) "SELECT 1 FROM ov.refs r WHERE r.name = :name UNION ALL $base AND f.path NOT IN (SELECT path FROM ov.files)" else base
        return query("SELECT count(*) FROM ($rows LIMIT :cap)", mapOf("name" to name, "cap" to cap)) { it.getInt(1) }.single()
    }

    /** Imports matching [where] (aliases `i`/`f`), masked like [decls]. */
    fun imports(where: String, params: Map<String, Any?> = emptyMap()): List<ImportRow> {
        val base = "SELECT i.fqn, i.alias, i.star, f.path FROM main.imports i JOIN main.files f ON f.id = i.file_id WHERE ($where)"
        val sql = if (overlay) {
            """SELECT i.fqn, i.alias, i.star, f.path FROM ov.imports i JOIN ov.files f ON f.id = i.file_id WHERE ($where)
           UNION ALL $base AND f.path NOT IN (SELECT path FROM ov.files)"""
        } else {
            base
        }
        return query(sql, params, ImportRow::of)
    }

    fun file(path: String): FileRow? {
        if (overlay) {
            val ov = query("SELECT *, 'ov' AS src FROM ov.files WHERE path = :path", mapOf("path" to path), FileRow::of).firstOrNull()
            if (ov != null) return ov.takeUnless { it.deleted }
        }
        return query("SELECT *, 'base' AS src FROM main.files WHERE path = :path", mapOf("path" to path), FileRow::of).firstOrNull()
    }

    /** Paths ending with the given suffix (`OrderService.kt`, `shop/OrderService.kt`). */
    fun filesBySuffix(suffix: String): List<String> {
        val like = mapOf("like" to "%" + Like.escape(suffix))
        val rows = query("SELECT path FROM main.files WHERE deleted = 0 AND path LIKE :like ESCAPE '\\'", like) { it.getString(1) }
        if (!overlay) return rows
        val ov = query("SELECT path, deleted FROM ov.files WHERE path LIKE :like ESCAPE '\\'", like) { it.getString(1) to (it.getInt(2) != 0) }
        val gone = ov.filter { it.second }.map { it.first }.toSet()
        return LinkedHashSet(rows.filter { it !in gone } + ov.filter { !it.second }.map { it.first }).toList()
    }

    /** Paths of files whose content holds [literal], at most [limit], overlay copies masking base ones. */
    fun filesContaining(literal: String, limit: Int): List<String> {
        val params = mapOf("lit" to literal, "limit" to limit)
        val base = "SELECT path FROM main.files WHERE deleted = 0 AND content IS NOT NULL AND instr(content, :lit) > 0"
        if (!overlay) return query("$base ORDER BY path LIMIT :limit", params) { it.getString(1) }
        val ov = query("SELECT path, deleted, content IS NOT NULL AND instr(content, :lit) > 0 FROM ov.files", params) { Triple(it.getString(1), it.getInt(2) != 0, it.getInt(3) != 0) }
        val masked = ov.map { it.first }.toSet()
        val hits = query("$base ORDER BY path", params) { it.getString(1) }.filter { it !in masked } + ov.filter { !it.second && it.third }.map { it.first }
        return hits.sortedWith(PathOrder).take(limit)
    }

    /** Every module of the index (`importers/ruian`), the root module as an empty string. */
    fun modules(): List<String> {
        val base = query("SELECT DISTINCT module FROM main.files WHERE deleted = 0 AND module IS NOT NULL", emptyMap()) { it.getString(1) }
        if (!overlay) return base
        return (base + query("SELECT DISTINCT module FROM ov.files WHERE deleted = 0 AND module IS NOT NULL", emptyMap()) { it.getString(1) }).distinct()
    }

    override fun close() = Timings.measure(TimedPart.OPEN) {
        statements.values.forEach { it.first.close() }
        db.close()
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, map: (java.sql.ResultSet) -> T): List<T> = Timings.measure(TimedPart.SQL) {
        val (statement, named) = statements.getOrPut(sql) {
            val named = NamedSql.parse(sql)
            db.prepareStatement(named.sql) to named
        }
        named.bind(statement, params)
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    private companion object {
        const val MAX_STATEMENTS = 24
    }
}
