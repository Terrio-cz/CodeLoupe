package codeloupe.write

import codeloupe.lang.DeclFact

/** What identifies a declaration inside its file regardless of where it stands: kind, place, name and parameter types. */
internal object DeclKeys {
    fun of(d: DeclFact): String =
        "${d.kind}|${d.container}|${d.name}|${d.receiver.orEmpty()}|${d.params.joinToString(",") { p -> p.type.filterNot(Char::isWhitespace) }}"
}
