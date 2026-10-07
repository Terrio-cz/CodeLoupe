package codeloupe.query

import java.sql.PreparedStatement

/** SQL with `:name` parameters, rewritten to JDBC `?` placeholders. */
internal class NamedSql private constructor(val sql: String, private val names: List<String>) {
    fun bind(statement: PreparedStatement, params: Map<String, Any?>) {
        names.forEachIndexed { i, name -> statement.setObject(i + 1, params[name]) }
    }

    companion object {
        fun parse(sql: String): NamedSql {
            val out = StringBuilder(sql.length)
            val names = ArrayList<String>()
            var quoted = false
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                if (c == '\'') quoted = !quoted
                if (!quoted && c == ':' && i + 1 < sql.length && sql[i + 1].isJavaIdentifierStart()) {
                    var end = i + 1
                    while (end < sql.length && sql[end].isJavaIdentifierPart()) end++
                    names += sql.substring(i + 1, end)
                    out.append('?')
                    i = end
                    continue
                }
                out.append(c)
                i++
            }
            return NamedSql(out.toString(), names)
        }
    }
}
