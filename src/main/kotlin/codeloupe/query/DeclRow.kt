package codeloupe.query

import java.sql.ResultSet

/** A declaration row joined with its file; [src] tells whether it came from the base or an overlay. */
data class DeclRow(
    val id: Long,
    val kind: String,
    val name: String,
    val container: String,
    val fqn: String,
    val receiver: String?,
    val params: String?,
    val paramCount: Int,
    val returns: String?,
    val modifiers: String,
    val supertypes: String,
    val startLine: Int,
    val declLine: Int,
    val endLine: Int,
    val sig: String,
    val hash: String,
    val local: Boolean,
    val parentId: Long?,
    val path: String,
    val module: String,
    val sourceSet: String,
    val src: String,
) {
    companion object {
        const val COLUMNS = """d.id, d.kind, d.name, d.container, d.fqn, d.receiver, d.params, d.param_count, d.returns, d.modifiers,
  d.supertypes, d.start_line, d.decl_line, d.end_line, d.sig, d.hash, d.local, d.parent_id, f.path, f.module, f.source_set"""

        fun of(rs: ResultSet) = DeclRow(
            id = rs.getLong("id"), kind = rs.getString("kind"), name = rs.getString("name"), container = rs.getString("container"),
            fqn = rs.getString("fqn"), receiver = rs.getString("receiver"), params = rs.getString("params"),
            paramCount = rs.getInt("param_count"), returns = rs.getString("returns"), modifiers = rs.getString("modifiers") ?: "",
            supertypes = rs.getString("supertypes") ?: "", startLine = rs.getInt("start_line"), declLine = rs.getInt("decl_line"),
            endLine = rs.getInt("end_line"), sig = rs.getString("sig") ?: "", hash = rs.getString("hash") ?: "",
            local = rs.getInt("local") != 0, parentId = rs.getLong("parent_id").takeUnless { rs.wasNull() },
            path = rs.getString("path"), module = rs.getString("module") ?: "", sourceSet = rs.getString("source_set") ?: "",
            src = rs.getString("src"),
        )
    }
}
