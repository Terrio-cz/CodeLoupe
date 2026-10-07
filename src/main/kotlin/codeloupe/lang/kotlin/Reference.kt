package codeloupe.lang.kotlin

/** A use of a name at [offset], before it is tied to the enclosing declaration; see [codeloupe.lang.RefFact]. */
internal data class Reference(
    val name: String,
    val offset: Int,
    val kind: String,
    val recv: String?,
    val bind: String? = null,
    val recvType: String? = null,
    val args: Int? = null,
)
