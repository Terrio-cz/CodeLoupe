package codeloupe.query

import java.sql.ResultSet

/** A reference row joined with its file; [declId] is the enclosing declaration in the same [src] database. */
data class RefRow(
    val name: String,
    val line: Int,
    val col: Int,
    val kind: String,
    val recv: String?,
    val declId: Long?,
    val bind: String?,
    val recvType: String?,
    val args: Int?,
    val path: String,
    val src: String,
) {
    companion object {
        const val COLUMNS = "r.name, r.line, r.col, r.kind, r.recv, r.decl_id, r.bind, r.recv_type, r.args, f.path"

        fun of(rs: ResultSet) = RefRow(
            name = rs.getString("name"), line = rs.getInt("line"), col = rs.getInt("col"), kind = rs.getString("kind"),
            recv = rs.getString("recv"), declId = rs.getLong("decl_id").takeUnless { rs.wasNull() }, bind = rs.getString("bind"),
            recvType = rs.getString("recv_type"), args = rs.getInt("args").takeUnless { rs.wasNull() }, path = rs.getString("path"),
            src = rs.getString("src"),
        )
    }
}
