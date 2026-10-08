package codeloupe.lang

/**
 * One declaration; [returns] is the declared type, or the [TypeSpec] of the initializer or expression body when
 * there is none. Lines are 1-based: [start] includes the KDoc above, [declStart] is the declaration itself.
 * [parent] and the container chain refer to enclosing declarations of the same file (-1 = none).
 */
data class DeclFact(
    val kind: String,
    val name: String,
    val container: String,
    val receiver: String?,
    val params: List<ParamFact>,
    val returns: String?,
    val modifiers: List<String>,
    val supertypes: List<String>,
    val start: Int,
    val declStart: Int,
    val end: Int,
    val sig: String,
    val hash: String,
    val local: Boolean,
    val parent: Int,
) {
    /**
     * Offsets into the text of the file the facts were read from, for the writing tools; the index does not store them. [startOffset]
     * is the first character of the documentation comment, else of the declaration, [endOffset] the end of the declaration,
     * [nameOffset] the start of its name, [bodyOpen] and [bodyClose] the braces of a type's body; -1 where there is none.
     */
    var startOffset = -1
    var endOffset = -1
    var nameOffset = -1
    var bodyOpen = -1
    var bodyClose = -1
}
