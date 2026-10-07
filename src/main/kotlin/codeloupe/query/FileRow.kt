package codeloupe.query

import java.sql.ResultSet

data class FileRow(val path: String, val packageName: String, val errors: Int, val deleted: Boolean, val content: String?, val src: String) {
    companion object {
        fun of(rs: ResultSet) = FileRow(
            path = rs.getString("path"), packageName = rs.getString("package") ?: "", errors = rs.getInt("errors"),
            deleted = rs.getInt("deleted") != 0, content = rs.getString("content"), src = rs.getString("src"),
        )
    }
}
