package codeloupe.query

/** `find`: declarations by name, `Type.member` or glob, one line per hit. */
object FindQuery {
    data class Args(
        val q: String?,
        val kind: String? = null,
        val module: String? = null,
        val test: Boolean? = null,
        val locals: Boolean = false,
        val limit: Int? = null,
        /** `search` ranks declarations for the words of [q]; `name` (the default) looks a name up. Words with spaces mean `search`. */
        val mode: String? = null,
    ) {
        val searching get() = mode == "search" || (mode == null && q != null && q.any(Char::isWhitespace) && '(' !in q)
    }

    fun run(view: View, args: Args): String {
        if (args.searching) return SearchQuery.run(view, args)
        val limit = args.limit ?: 30
        val filter = DeclFilter.of(args.kind, args.module, args.test, args.locals)
        val extra = filter.sql
        val params = HashMap(filter.params)
        val q = QueryName.parse(args.q)
        val name = q.name
        // A pattern no index can seek scans the narrow name index (covering) instead of every declaration row: 3x faster.
        var rows = if (name.contains('*') || name.contains('?')) {
            view.decls("d.id IN (SELECT id FROM {db}.decls WHERE name GLOB :name)$extra", params + ("name" to name))
        } else {
            view.decls("d.name = :name$extra", params + ("name" to name)).ifEmpty {
                view.decls("d.id IN (SELECT id FROM {db}.decls WHERE name LIKE :like ESCAPE '\\')$extra", params + ("like" to "%${Like.escape(name)}%"))
            }
        }
        rows = rows.filter { DeclMatch.qualifier(it, q.qualifier) }.sortedWith(Resolver.BY_PATH_AND_LINE)
        if (rows.isEmpty()) return "no declaration matches \"${args.q}\""
        return rows.take(limit).joinToString("\n", transform = Format::head) +
            Format.more(rows.size, limit, " (narrow with kind/module or a qualified name)")
    }
}
