package codeloupe.tracker.mirror

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

// Positional JDBC helpers for the mirror's small statements.

internal fun Connection.exec(sql: String, vararg args: Any?) = prepareStatement(sql).use { it.bindAll(args.toList()); it.executeUpdate() }

internal fun <T> Connection.batch(sql: String, rows: List<T>, values: (T) -> List<Any?>) {
    if (rows.isEmpty()) return
    prepareStatement(sql).use { s ->
        rows.forEach { s.bindAll(values(it)); s.addBatch() }
        s.executeBatch()
    }
}

internal fun <T> Connection.query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    s.bindAll(args.toList())
    s.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
}

private fun PreparedStatement.bindAll(args: List<Any?>) = args.forEachIndexed { i, v -> setObject(i + 1, v) }
