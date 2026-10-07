package codeloupe.query.usages

import codeloupe.query.DeclRow

/**
 * A call's candidates narrowed by argument count: functions whose parameters fit (defaults and `vararg` make a
 * range); `x()` on a property (`invoke`) only when no function of that name fits.
 */
internal class Arguments(private val cache: IndexCache) {
    fun narrow(r: Resolution, args: Int?): Resolution {
        val functions = r.decls.filter { it.kind == "fun" }
        if (functions.isEmpty()) return r
        val fits = { d: DeclRow -> d.kind != "fun" || args == null || args < 0 || fits(d, args) }
        val fitting = functions.filter(fits)
        return when {
            fitting.isNotEmpty() -> r.copy(decls = r.decls.filter { it.kind != "property" && fits(it) }, further = r.further.filter(fits))
            r.decls.any { it.kind != "fun" } -> r.filter { it.kind != "fun" }
            else -> r
        }
    }

    fun fits(d: DeclRow, args: Int): Boolean {
        val params = cache.params(d)
        val required = params.count { !it.default && !it.vararg }
        return args >= required && (params.any { it.vararg } || args <= params.size)
    }
}
