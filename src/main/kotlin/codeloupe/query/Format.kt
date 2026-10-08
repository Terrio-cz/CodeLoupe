package codeloupe.query

/** The compact text lines every tool answers with. */
internal object Format {
    /** `path:start-end  [Container] signature` */
    fun head(d: DeclRow): String = "${d.path}:${range(d)}  ${inContainer(d)}"

    /** `[Container] signature` */
    fun inContainer(d: DeclRow): String = "${if (d.container.isNotEmpty()) "[${d.container}] " else ""}${SigText.plain(d.sig)}"

    /** `  start-end  signature`, indented by nesting depth. */
    fun member(d: DeclRow, depth: Int): String = "  ".repeat(depth + 1) + "${range(d)}  ${SigText.plain(d.sig)}"

    /** `start-end`, or `line` for a declaration on one line. */
    fun range(d: DeclRow): String = range(d.startLine, d.endLine)

    fun range(start: Int, end: Int): String = if (start == end) "$start" else "$start-$end"

    fun more(total: Int, shown: Int, hint: String = ""): String = if (total > shown) "\n… +${total - shown} more$hint" else ""
}
