package codeloupe.lang.kotlin

import codeloupe.lang.ParamFact

/** What a declaration says about itself; position, nesting and hash are added by [KotlinExtractor]. */
internal data class DeclShape(
    val kind: String,
    val name: String,
    val modifiers: Modifiers,
    val params: List<ParamFact> = emptyList(),
    val receiver: String? = null,
    val returns: String? = null,
    val supertypes: List<String> = emptyList(),
    /** Fixed signature; null = header text up to the body. */
    val sig: String? = null,
)
