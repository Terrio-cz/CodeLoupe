package codeloupe.query

/** The `kind`, `module`, `test` and `locals` options of `find` as a SQL condition on aliases `d` and `f`, led by ` AND ` when not empty. */
internal class DeclFilter private constructor(val sql: String, val params: Map<String, Any?>) {
    companion object {
        fun of(kind: String?, module: String?, test: Boolean?, locals: Boolean): DeclFilter {
            val conds = ArrayList<String>()
            val params = HashMap<String, Any?>()
            if (!kind.isNullOrEmpty()) {
                conds += "d.kind = :kind"
                params["kind"] = kind
            }
            if (!module.isNullOrEmpty()) {
                conds += ModuleScope.sql()
                params += ModuleScope.params(module)
            }
            if (test == true) conds += "f.source_set LIKE '%test%'"
            if (test == false) conds += "f.source_set NOT LIKE '%test%'"
            if (!locals) conds += "d.local = 0"
            return DeclFilter(if (conds.isEmpty()) "" else " AND " + conds.joinToString(" AND "), params)
        }
    }
}
