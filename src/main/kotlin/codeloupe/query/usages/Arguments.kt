package codeloupe.query.usages

import codeloupe.query.DeclRow

/** Overload narrowing by argument count; parameters with defaults and varargs make a range. */
internal object Arguments {
    fun narrow(r: Resolution, args: Int?): Resolution {
        if (args == null || args < 0 || r.decls.none { it.kind == "fun" }) return r
        val fitting = r.filter { it.kind != "fun" || fits(it, args) }
        return if (fitting.decls.isEmpty()) r else fitting
    }

    fun fits(d: DeclRow, args: Int): Boolean {
        if ("vararg" in d.sig) return args >= d.paramCount - 1 - defaults(d.sig)
        return args <= d.paramCount && args >= d.paramCount - defaults(d.sig)
    }

    /** `=` at the top level of the parameter list: a default value (not `==`, `!=`, `<=`, `>=`, `->`). */
    private fun defaults(sig: String): Int {
        val open = sig.indexOf('(')
        if (open < 0) return 0
        var depth = 0
        var count = 0
        for (i in open until sig.length) {
            val c = sig[i]
            when (c) {
                '(', '<', '[', '{' -> depth++
                ')', '>', ']', '}' -> if (c == '>' && sig.getOrNull(i - 1) == '-') Unit else if (--depth == 0) return count
                '=' -> if (depth == 1 && sig.getOrNull(i - 1) !in NOT_DEFAULT && sig.getOrNull(i + 1) != '=' && sig.getOrNull(i + 1) != '>') count++
            }
        }
        return count
    }

    private val NOT_DEFAULT = setOf('=', '!', '<', '>')
}
