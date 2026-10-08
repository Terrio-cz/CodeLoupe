package codeloupe.lang

import kotlinx.serialization.Serializable

/** A value parameter; [default] when it has a default value, [vararg] for `vararg`. */
@Serializable
data class ParamFact(val name: String, val type: String, val default: Boolean = false, val vararg: Boolean = false)
