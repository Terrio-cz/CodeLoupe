package codeloupe.query

/** `find`: declarations by name, `Type.member` or glob, one line per hit. */
object FindQuery {
    data class Args(
        val q: String?,
        val kind: String? = null,
        val module: String? = null,
        val test: Boolean? = null,
        val locals: Boolean = false,
        val limit: Int = 30,
    )

    fun run(view: View, args: Args): String {
        val conds = ArrayList<String>()
        val params = HashMap<String, Any?>()
        if (!args.kind.isNullOrEmpty()) {
            conds += "d.kind = :kind"
            params["kind"] = args.kind
        }
        if (!args.module.isNullOrEmpty()) {
            conds += "(f.module = :module OR f.module LIKE :modulePrefix ESCAPE '\\')"
            params["module"] = args.module
            params["modulePrefix"] = Like.escape(args.module) + "/%"
        }
        if (args.test == true) conds += "f.source_set LIKE '%test%'"
        if (args.test == false) conds += "f.source_set NOT LIKE '%test%'"
        if (!args.locals) conds += "d.local = 0"
        val extra = if (conds.isEmpty()) "" else " AND " + conds.joinToString(" AND ")
        val q = QueryName.parse(args.q)
        val name = q.name
        var rows = if (name.contains('*') || name.contains('?')) {
            view.decls("d.name GLOB :name$extra", params + ("name" to name))
        } else {
            view.decls("d.name = :name$extra", params + ("name" to name)).ifEmpty {
                view.decls("d.name LIKE :like ESCAPE '\\'$extra", params + ("like" to "%${Like.escape(name)}%"))
            }
        }
        rows = rows.filter { DeclMatch.qualifier(it, q.qualifier) }.sortedWith(Resolver.BY_PATH_AND_LINE)
        if (rows.isEmpty()) return "no declaration matches \"${args.q}\""
        return rows.take(args.limit).joinToString("\n", transform = Format::head) +
            Format.more(rows.size, args.limit, " (narrow with kind/module or a qualified name)")
    }
}
