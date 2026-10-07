package codeloupe.query

import codeloupe.index.Store
import java.nio.file.Path
import java.sql.PreparedStatement

/**
 * Read view over a base index, optionally with a worktree overlay attached as `ov`. Every query goes through
 * here so overlay masking (a worktree's copy of a file hides the base copy) lives in one place.
 */
class View(baseFile: Path, overlayFile: Path? = null) : AutoCloseable {
    private val db = Store.open(baseFile, readOnly = true)
    private val overlay = overlayFile != null
    private val statements = HashMap<String, Pair<PreparedStatement, NamedSql>>()

    init {
        if (overlayFile != null) {
            db.createStatement().use { it.execute("ATTACH DATABASE '${overlayFile.toString().replace("'", "''")}' AS ov") }
        }
    }

    /** Declarations matching [where] (written against aliases `d`/`f`, with `:params`) over base and overlay. */
    fun decls(where: String, params: Map<String, Any?> = emptyMap(), tail: String = ""): List<DeclRow> {
        val base = "SELECT ${DeclRow.COLUMNS}, 'base' AS src FROM main.decls d JOIN main.files f ON f.id = d.file_id WHERE ($where)"
        val sql = if (overlay) {
            """SELECT * FROM (SELECT ${DeclRow.COLUMNS}, 'ov' AS src FROM ov.decls d JOIN ov.files f ON f.id = d.file_id WHERE ($where)
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

    override fun close() {
        statements.values.forEach { it.first.close() }
        db.close()
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, map: (java.sql.ResultSet) -> T): List<T> {
        val (statement, named) = statements.getOrPut(sql) {
            val named = NamedSql.parse(sql)
            db.prepareStatement(named.sql) to named
        }
        named.bind(statement, params)
        return statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }
}
