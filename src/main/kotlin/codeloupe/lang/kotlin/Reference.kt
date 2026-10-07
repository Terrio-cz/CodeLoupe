package codeloupe.lang.kotlin

/** A use of a name at [offset], before it is tied to the enclosing declaration. */
internal data class Reference(val name: String, val offset: Int, val kind: String, val recv: String?)
