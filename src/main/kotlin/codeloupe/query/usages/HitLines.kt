package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.PathOrder

/** Usages grouped by file and enclosing declaration, one code line per hit line. */
internal object HitLines {
    private const val MAX_CODE = 110

    /** Hits ordered by path and line, several on one line merged under the surest label. */
    fun lines(usages: List<Usage>): List<Usage> =
        usages.groupBy { it.ref.path to it.ref.line }.values.map { same -> same.minBy { it.label.ordinal } }
            .sortedWith(compareBy<Usage, String>(PathOrder) { it.ref.path }.thenBy { it.ref.line })

    /** `path`, then per enclosing declaration its short signature — left out when the first hit is its own line. */
    fun render(hits: List<Usage>, cache: IndexCache): String = buildString {
        var path: String? = null
        var owner: DeclRow? = null
        for (hit in hits) {
            val newFile = hit.ref.path != path
            if (newFile) appendLine(hit.ref.path)
            val code = cache.line(hit.ref.path, hit.ref.line)
            if ((newFile || hit.owner != owner) && !showsOwner(hit, code)) appendLine("  " + ownerLine(hit.owner))
            path = hit.ref.path
            owner = hit.owner
            appendLine("  ${hit.ref.line} ${hit.label.mark} ${snippet(code, hit.ref.col)}")
        }
    }.trimEnd()

    // The hit's own line is the owner's declaration, shown whole: it names the owner already.
    private fun showsOwner(hit: Usage, code: String) = hit.ref.line == hit.owner?.declLine && code.trim().length <= MAX_CODE

    fun ownerLine(d: DeclRow?): String = if (d == null) "(file level)" else ShortSignature.of(d)

    /** The trimmed line, or a window around the hit when it is long. */
    fun snippet(line: String, col: Int): String {
        val text = line.trim()
        if (text.length <= MAX_CODE) return text
        val at = (col - 1 - (line.length - line.trimStart().length)).coerceIn(0, text.length)
        val from = (at - MAX_CODE / 3).coerceAtLeast(0)
        val to = (from + MAX_CODE).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to) + (if (to < text.length) "…" else "")
    }
}
