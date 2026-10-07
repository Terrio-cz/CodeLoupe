package codeloupe.query

/** `symbol`: the source of one declaration (KDoc, annotations, body); large types collapse to header + members. */
object SymbolQuery {
    private const val BIG_TYPE_LINES = 120
    private const val HEADER_LINES = 15

    data class Args(val name: String?, val full: Boolean = false, val all: Boolean = false, val limit: Int = 10)

    fun run(view: View, args: Args): String {
        val name = args.name.orEmpty()
        val rows = Resolver.resolve(view, name)
        if (rows.isEmpty()) return "no declaration \"$name\"" + Members.suggest(view, name)
        if (rows.size > 1 && !args.all) {
            return "${rows.size} declarations match \"$name\" — qualify it (Type.member, member(ParamType, …)) or pass all=true:\n" +
                rows.take(20).joinToString("\n", transform = Format::head) + Format.more(rows.size, 20)
        }
        return rows.take(args.limit).joinToString("\n\n") { body(view, it, args.full) } + Format.more(rows.size, args.limit)
    }

    private fun body(view: View, d: DeclRow, full: Boolean): String {
        val lines = view.file(d.path)!!.content.orEmpty().split('\n')
        fun slice(from: Int, to: Int): String {
            val start = (from - 1).coerceIn(0, lines.size)
            val end = to.coerceIn(start, lines.size)
            return lines.subList(start, end).joinToString("\n") { it.removeSuffix("\r") }
        }
        val header = "${d.path}:${d.startLine}-${d.endLine}  ${d.fqn}  hash=${d.hash}"
        if (full || d.kind !in OutlineQuery.TYPE_KINDS || d.endLine - d.startLine <= BIG_TYPE_LINES) {
            return "$header\n${slice(d.startLine, d.endLine)}"
        }
        val members = Members.of(view, d)
        val firstMember = members.minOfOrNull { it.decl.startLine } ?: d.endLine
        val top = slice(d.startLine, minOf(firstMember - 1, d.declLine + HEADER_LINES))
        return "$header\n$top\n  // ${d.endLine - d.startLine + 1} lines; members (symbol \"${d.name}.<member>\" for one, full=true for all):\n" +
            members.joinToString("\n") { Format.member(it.decl, it.depth) } + "\n}"
    }
}
