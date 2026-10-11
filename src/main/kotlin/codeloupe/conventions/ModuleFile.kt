package codeloupe.conventions

import codeloupe.lang.DeclFact
import codeloupe.lang.FileFacts

/** One source file of a module with the facts read from it; [path] is relative to the module and uses '/'. */
class ModuleFile(val path: String, val test: Boolean, text: String, val facts: FileFacts) {
    val stem: String = path.substringAfterLast('/').substringBeforeLast('.')

    /** The code with comments and string contents blanked, so a count never takes a word from documentation or a literal. */
    val code: String by lazy { CodeText.blank(text) }

    val topLevel: List<DeclFact> by lazy { facts.decls.filter { it.parent == -1 && !it.local } }

    val types: List<DeclFact> by lazy { topLevel.filter { it.kind in TYPE_KINDS } }

    val packageName: String get() = facts.packageName

    companion object {
        val TYPE_KINDS = setOf("class", "interface", "object", "enum", "annotation")
    }
}
