package codeloupe.query

/** Escaping for `LIKE … ESCAPE '\'`. */
internal object Like {
    fun escape(text: String): String = text.replace(Regex("[%_\\\\]")) { "\\" + it.value }

    /** What `text LIKE '%' || escape(suffix)` answers in SQLite: a suffix match that ignores the case of ASCII letters only. */
    fun endsWith(text: String, suffix: String): Boolean {
        val from = text.length - suffix.length
        if (from < 0) return false
        for (i in suffix.indices) if (fold(text[from + i]) != fold(suffix[i])) return false
        return true
    }

    private fun fold(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c
}
