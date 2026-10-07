package codeloupe.query

/** The compact text lines every tool answers with. */
internal object Format {
    /** `path:start-end  [Container] signature` */
    fun head(d: DeclRow): String = "${d.path}:${d.startLine}-${d.endLine}  ${if (d.container.isNotEmpty()) "[${d.container}] " else ""}${d.sig}"

    /** `  start-end  signature`, indented by nesting depth. */
    fun member(d: DeclRow, depth: Int): String = "  ".repeat(depth + 1) + "${d.startLine}-${d.endLine}  ${d.sig}"

    fun more(total: Int, shown: Int, hint: String = ""): String = if (total > shown) "\n… +${total - shown} more$hint" else ""
}
