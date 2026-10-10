package codeloupe.query

/** Non-local declarations nested in a type, with their depth below it. */
internal object Members {
    data class Member(val decl: DeclRow, val depth: Int)

    fun of(view: View, type: DeclRow): List<Member> {
        val prefix = if (type.container.isNotEmpty()) "${type.container}.${type.name}" else type.name
        val prefixDepth = prefix.split('.').size
        return view.decls(
            "f.path = :path AND d.local = 0 AND (d.container = :prefix OR d.container LIKE :like ESCAPE '\\')",
            mapOf("path" to type.path, "prefix" to prefix, "like" to Like.escape(prefix) + ".%"),
            "ORDER BY start_line",
        ).map { Member(it, it.container.split('.').size - prefixDepth) }
    }

    /** Declarations whose name contains the queried name, for "did you mean". */
    fun suggest(view: View, query: String): String {
        Resolver.locatorFile(query)?.let { file -> return if (Resolver.resolvePath(view, file) == null) "\nno indexed file \"$file\" (to be created, or not in this worktree)" else "" }
        val name = QueryName.parse(query).name
        if (name.isEmpty()) return ""
        val rows = view.decls("d.name LIKE :like ESCAPE '\\' AND d.local = 0", mapOf("like" to "%${Like.escape(name)}%"), "LIMIT 8")
        return if (rows.isEmpty()) "" else "\nsimilar:\n" + rows.joinToString("\n", transform = Format::head)
    }
}
