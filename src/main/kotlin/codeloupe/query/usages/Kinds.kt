package codeloupe.query.usages

import codeloupe.query.DeclRow

/** Declaration kinds by the role a name can play, and what follows from a declaration's own facts. */
internal object Kinds {
    val TYPES = setOf("class", "interface", "object", "enum", "companion", "annotation", "typealias")
    val CLASSIFIERS = TYPES - "typealias" + "enum_entry"

    fun isMember(d: DeclRow) = !d.local && (d.container.isNotEmpty() || d.receiver != null)

    /** A private declaration is out of reach outside its own file. */
    fun accessible(d: DeclRow, path: String) = d.path == path || "private" !in d.modifiers.split(' ')

    /** `T`, `K2`: a generic parameter, not a type the index can know. */
    fun isTypeParameter(name: String) = name.length <= 2 && name.first().isUpperCase() && name.all { it.isUpperCase() || it.isDigit() }

    /** `com.example.Type.member` -> `com.example`. */
    fun packageOf(d: DeclRow): String {
        val local = if (d.container.isEmpty()) d.name else "${d.container}.${d.name}"
        return d.fqn.removeSuffix(local).removeSuffix(".")
    }
}
