package codeloupe.query

import java.sql.ResultSet

data class ImportRow(val fqn: String, val alias: String?, val star: Boolean, val path: String) {
    companion object {
        fun of(rs: ResultSet) = ImportRow(rs.getString("fqn"), rs.getString("alias"), rs.getInt("star") != 0, rs.getString("path"))
    }
}
