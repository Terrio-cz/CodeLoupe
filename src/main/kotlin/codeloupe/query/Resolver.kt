package codeloupe.query

/** Declarations a query names: `Type.member`, `member(ParamType)`, `pkg.Type` or `path/File.kt:line`. */
internal object Resolver {
    private val AT_LINE = Regex("(.+\\.(?:kt|kts|java)):(\\d+)", RegexOption.IGNORE_CASE)

    val BY_PATH_AND_LINE: Comparator<DeclRow> = Comparator<DeclRow> { a, b -> PathOrder.compare(a.path, b.path) }.thenBy { it.startLine }

    fun resolve(view: View, query: String, kinds: Set<String>? = null): List<DeclRow> {
        AT_LINE.matchEntire(query)?.let { m -> return innermostAt(view, m.groupValues[1], m.groupValues[2].toInt()) }
        val q = QueryName.parse(query)
        if (q.parts.isEmpty()) return emptyList()
        val named = view.decls("d.name = :name", mapOf("name" to q.name)).filter { DeclMatch.params(it, q.params) }
        var rows = named.filter { DeclMatch.qualifier(it, q.qualifier) }.ifEmpty { companionMembers(view, named, q.qualifier) }
        if (kinds != null) rows = rows.filter { it.kind in kinds }
        if (rows.any { !it.local }) rows = rows.filter { !it.local }
        return rows.sortedWith(BY_PATH_AND_LINE)
    }

    /** `Type.member` for a member of Type's companion object, as Kotlin code calls it. */
    private fun companionMembers(view: View, named: List<DeclRow>, qualifier: String): List<DeclRow> {
        if (qualifier.isEmpty()) return emptyList()
        return named.filter { d ->
            val owner = d.fqn.substringBeforeLast('.', "")
            '.' in owner && DeclMatch.qualifier(d.copy(fqn = owner.substringBeforeLast('.') + "." + d.name, receiver = null), qualifier) &&
                view.decls("d.fqn = :fqn AND d.kind = 'companion'", mapOf("fqn" to owner)).isNotEmpty()
        }
    }

    /** A path, or the only indexed path ending with it. */
    fun resolvePath(view: View, path: String): String? {
        val p = path.replace('\\', '/').removePrefix("./")
        if (view.file(p) != null) return p
        return view.filesBySuffix(if (p.startsWith("/")) p else "/$p").singleOrNull()
    }

    private fun innermostAt(view: View, file: String, line: Int): List<DeclRow> {
        val path = resolvePath(view, file) ?: return emptyList()
        return view.decls(
            "f.path = :path AND d.start_line <= :line AND d.end_line >= :line", mapOf("path" to path, "line" to line),
            "ORDER BY (end_line - start_line) ASC LIMIT 1",
        )
    }
}
