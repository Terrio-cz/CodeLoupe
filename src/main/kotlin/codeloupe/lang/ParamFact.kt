package codeloupe.lang

/** A value parameter; [default] when it has a default value, [vararg] for `vararg`. */
data class ParamFact(val name: String, val type: String, val default: Boolean = false, val vararg: Boolean = false)
