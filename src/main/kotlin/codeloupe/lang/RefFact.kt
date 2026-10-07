package codeloupe.lang

/** An identifier that is not a declaration name: [kind] is call, nav, type, callable_ref, named_arg or name. */
data class RefFact(val name: String, val line: Int, val col: Int, val kind: String, val recv: String?, val decl: Int)
