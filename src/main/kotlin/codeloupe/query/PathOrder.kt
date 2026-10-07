package codeloupe.query

import java.text.Collator
import java.util.Locale

/**
 * Locale-independent ordering of paths, equal to Unicode root collation for ASCII: punctuation before digits
 * before letters, letters case-insensitive first and lowercase before uppercase on a tie.
 */
internal object PathOrder : Comparator<String> {
    private const val ORDER = " _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$0123456789abcdefghijklmnopqrstuvwxyz"
    private val primary = IntArray(128) { -1 }.also { weights ->
        ORDER.forEachIndexed { i, c -> weights[c.code] = i }
        for (c in 'A'..'Z') weights[c.code] = weights[c.lowercaseChar().code]
    }
    private val fallback: Collator = Collator.getInstance(Locale.ROOT)

    override fun compare(a: String, b: String): Int {
        if (!isPrintableAscii(a) || !isPrintableAscii(b)) return fallback.compare(a, b)
        for (i in 0 until minOf(a.length, b.length)) {
            val d = primary[a[i].code] - primary[b[i].code]
            if (d != 0) return d
        }
        if (a.length != b.length) return a.length - b.length
        for (i in a.indices) {
            if (a[i] != b[i]) return if (a[i].isLowerCase()) -1 else 1
        }
        return 0
    }

    private fun isPrintableAscii(s: String) = s.all { it.code in 0x20..0x7E }
}
