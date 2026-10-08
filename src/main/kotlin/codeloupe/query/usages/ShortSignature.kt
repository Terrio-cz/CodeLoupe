package codeloupe.query.usages

import codeloupe.query.DeclRow
import codeloupe.query.SigText

/** `[Container] fun Type.name(…)`: a declaration named compactly, parameters elided, for lines that only locate code. */
internal object ShortSignature {
    private const val MAX = 100
    private const val MIN_CONTAINER = 20

    /** `path:line  [Container] fun name(…)`, the path without [dir] (a directory the whole list shares). */
    fun located(d: DeclRow, dir: String = ""): String = "${d.path.removePrefix(dir)}:${d.declLine}  ${of(d)}"

    fun of(d: DeclRow): String {
        val sig = SigText.plain(d.sig)
        val at = sig.indexOf(d.name)
        val open = if (at < 0) -1 else sig.indexOf('(', at + d.name.length)
        val head = if (open >= 0 && sig.substring(at + d.name.length, open).trim('`', ' ').isEmpty()) {
            sig.substring(0, open) + if (sig.startsWith("()", open)) "()" else "(…)"
        } else {
            sig
        }
        if (d.container.isEmpty()) return clip(head, MAX)
        // A long container (a backticked test name) gives way before the signature does.
        val room = MAX - head.length - 3
        val keep = maxOf(room, MIN_CONTAINER)
        val container = if (d.container.length <= keep) d.container else "…" + d.container.takeLast(keep - 1)
        return clip("[$container] $head", MAX)
    }

    private fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"
}
