package codeloupe.query.usages

import codeloupe.query.DeclRow

/** Declaration kinds by the role a name can play. */
internal object Kinds {
    val TYPES = setOf("class", "interface", "object", "enum", "companion", "annotation", "typealias")
    val CLASSIFIERS = TYPES - "typealias" + "enum_entry"
    val VALUES = setOf("property", "object", "enum_entry", "companion", "class", "enum", "interface", "annotation", "typealias")

    fun isType(d: DeclRow) = d.kind in TYPES

    /** `T`, `K2`: a generic parameter, not a type the index can know. */
    fun isTypeParameter(name: String) = name.length <= 2 && name.first().isUpperCase() && name.all { it.isUpperCase() || it.isDigit() }

    fun isMember(d: DeclRow) = !d.local && (d.container.isNotEmpty() || d.receiver != null)

    /** `com.example.Type.member` -> `com.example`. */
    fun packageOf(d: DeclRow): String {
        val local = if (d.container.isEmpty()) d.name else "${d.container}.${d.name}"
        return d.fqn.removeSuffix(local).removeSuffix(".")
    }
}
