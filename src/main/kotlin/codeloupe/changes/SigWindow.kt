package codeloupe.changes

import kotlin.math.max
import kotlin.math.min

/**
 * A long signature cut to what a reader needs. A class with a constructor of twenty parameters is one line of the
 * answer, not a paragraph; a changed one keeps the stretch around what differs, which is what the change is.
 */
internal object SigWindow {
    private const val LIMIT = 160
    private const val CONTEXT = 30
    private const val DIFF_LIMIT = LIMIT + 2 * CONTEXT

    fun clip(sig: String, limit: Int = LIMIT): String = if (sig.length <= limit) sig else sig.take(limit - 1) + "…"

    /** [new] and [old] as they are when both are short, else the part around where they differ. */
    fun around(new: String, old: String): Pair<String, String> {
        if (new.length <= LIMIT && old.length <= LIMIT) return new to old
        val shortest = min(new.length, old.length)
        var head = 0
        while (head < shortest && new[head] == old[head]) head++
        var tail = 0
        while (tail < shortest - head && new[new.length - 1 - tail] == old[old.length - 1 - tail]) tail++
        fun cut(sig: String): String {
            val from = max(0, head - CONTEXT)
            val to = min(sig.length, sig.length - tail + CONTEXT)
            return clip((if (from > 0) "…" else "") + sig.substring(from, to) + (if (to < sig.length) "…" else ""), DIFF_LIMIT)
        }
        return cut(new) to cut(old)
    }
}
