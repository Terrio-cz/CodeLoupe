package codeloupe.lang

/**
 * String helpers with JavaScript semantics, so signatures and receivers match the index format byte for byte
 * (JS `\s` and `trim()` know more whitespace than Java's ASCII-only `\s`).
 */
object JsText {
    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    /** Collapses every whitespace run to one space and trims. */
    fun squash(s: String): String {
        val out = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (isSpace(c)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
        }
        return out.toString()
    }

    fun trim(s: String): String = s.trim(::isSpace)

    fun removeSpaces(s: String): String = s.filterNot(::isSpace)

    /** `` `weird name` `` -> `weird name`. */
    fun bare(s: String): String = if (s.startsWith('`') && s.endsWith('`')) s.substring(1, maxOf(1, s.length - 1)) else s
}
