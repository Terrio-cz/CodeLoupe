package codeloupe.lang

import kotlinx.serialization.Serializable

/**
 * An identifier that is not a declaration name: [kind] is call, nav, type, callable_ref, named_arg or name.
 * [bind] is set when the name is bound inside code (parameter, lambda or loop variable, local declaration): the
 * binding's [TypeSpec], "" when unknown, prefixed with [BEYOND_CLASS] when a class body lies between binding and use.
 * [recvType] is the spec of the receiver (for an unqualified name: of a lambda's implicit receiver). [args] counts
 * the arguments of a call, -1 with a spread.
 */
@Serializable
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
) {
    companion object {
        const val BEYOND_CLASS = "^"
    }
}
