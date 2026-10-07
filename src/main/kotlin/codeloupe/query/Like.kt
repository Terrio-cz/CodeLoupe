package codeloupe.query

/** Escaping for `LIKE … ESCAPE '\'`. */
internal object Like {
    fun escape(text: String): String = text.replace(Regex("[%_\\\\]")) { "\\" + it.value }
}
