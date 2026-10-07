package codeloupe.lang

/**
 * An identifier that is not a declaration name: [kind] is call, nav, type, callable_ref, named_arg or name.
 * [bind] is set when the name is bound inside code (parameter, lambda or loop variable, local declaration): the
 * binding's type text, "" when unknown. [recvType] is the same for a receiver that is such a name. [args] counts
 * the arguments of a call, -1 with a spread.
 */
data class RefFact(
    val name: String,
    val line: Int,
    val col: Int,
    val kind: String,
    val recv: String?,
    val decl: Int,
    val bind: String? = null,
    val recvType: String? = null,
    val args: Int? = null,
)
