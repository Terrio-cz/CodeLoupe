package codeloupe.query

/**
 * Locale-independent ordering of paths, equal to Unicode root collation for ASCII: punctuation before digits
 * before letters, letters case-insensitive first and lowercase before uppercase on a tie. Other characters sort
 * after ASCII by their lowercase code point, so the order stays total for any mix of paths.
 */
internal object PathOrder : Comparator<String> {
    private const val ORDER = " _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$0123456789abcdefghijklmnopqrstuvwxyz"
    private val asciiWeights = IntArray(128) { it + ORDER.length }.also { weights ->
        ORDER.forEachIndexed { i, c -> weights[c.code] = i }
        for (c in 'A'..'Z') weights[c.code] = weights[c.lowercaseChar().code]
    }

    override fun compare(a: String, b: String): Int {
        for (i in 0 until minOf(a.length, b.length)) {
            val d = weight(a[i]) - weight(b[i])
            if (d != 0) return d
        }
        if (a.length != b.length) return a.length - b.length
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            if (a[i].isLowerCase() != b[i].isLowerCase()) return if (a[i].isLowerCase()) -1 else 1
            return a[i].compareTo(b[i])
        }
        return 0
    }

    private fun weight(c: Char): Int = if (c.code < 128) asciiWeights[c.code] else 128 + ORDER.length + c.lowercaseChar().code
}
