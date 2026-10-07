package codeloupe.query

import codeloupe.lang.JsText

/** ``a.b.`c d`.e(Int, String)`` -> parts `a, b, c d, e`, params `Int, String` (null without parentheses). */
data class QueryName(val parts: List<String>, val params: List<String>?) {
    val name: String get() = parts.lastOrNull() ?: ""

    val qualifier: String get() = parts.dropLast(1).joinToString(".")

    companion object {
        private val CALL = Regex("(.*?)\\((.*)\\)", RegexOption.DOT_MATCHES_ALL)

        fun parse(query: String?): QueryName {
            var q = JsText.trim(query.orEmpty())
            var params: List<String>? = null
            CALL.matchEntire(q)?.let { m ->
                q = m.groupValues[1]
                params = m.groupValues[2].split(',').map(JsText::trim).filter { it.isNotEmpty() }
            }
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var backticked = false
            for (c in q) {
                when {
                    c == '`' -> backticked = !backticked
                    c == '.' && !backticked -> { parts += current.toString(); current.clear() }
                    else -> current.append(c)
                }
            }
            parts += current.toString()
            return QueryName(parts.filter { it.isNotEmpty() }, params)
        }
    }
}
