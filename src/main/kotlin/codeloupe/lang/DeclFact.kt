package codeloupe.lang

/**
 * One declaration. Lines are 1-based: [start] includes the KDoc above, [declStart] is the declaration itself.
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
)
