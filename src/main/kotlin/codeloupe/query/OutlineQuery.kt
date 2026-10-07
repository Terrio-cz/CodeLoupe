package codeloupe.query

import codeloupe.lang.JsText

/** `outline`: members of a file or a type with line ranges and signatures, no bodies. */
object OutlineQuery {
    val TYPE_KINDS = setOf("class", "interface", "object", "enum", "companion", "annotation")
    private val CODE_FILE = Regex("\\.(kt|kts|java)$", RegexOption.IGNORE_CASE)

    fun run(view: View, target: String?): String {
        val t = JsText.trim(target.orEmpty())
        return if ('/' in t || CODE_FILE.containsMatchIn(t)) ofFile(view, t) else ofType(view, t)
    }

    private fun ofFile(view: View, target: String): String {
        val path = Resolver.resolvePath(view, target)
        if (path == null) {
            val hits = view.filesBySuffix("/" + target.trimStart('/'))
            return if (hits.isEmpty()) "no indexed file \"$target\"" else "ambiguous file \"$target\":\n" + hits.take(20).joinToString("\n")
        }
        val file = view.file(path)!!
        val lines = view.decls("f.path = :path AND d.local = 0", mapOf("path" to path), "ORDER BY start_line")
            .map { Format.member(it, if (it.container.isEmpty()) 0 else it.container.split('.').size).removePrefix("  ") }
        val facts = buildString {
            append("${file.content.orEmpty().split('\n').size} lines")
            if (file.packageName.isNotEmpty()) append(", package ${file.packageName}")
            if (file.errors != 0) append(", ${file.errors} parse errors")
        }
        return "$path  ($facts)\n" + lines.joinToString("\n")
    }

    private fun ofType(view: View, target: String): String {
        val types = Resolver.resolve(view, target, TYPE_KINDS)
        if (types.isEmpty()) return "no type named \"$target\"" + Members.suggest(view, target)
        if (types.size > 1) return "\"$target\" is ambiguous:\n" + types.take(20).joinToString("\n", transform = Format::head)
        val type = types[0]
        return Format.head(type) + "\n" + Members.of(view, type).joinToString("\n") { Format.member(it.decl, it.depth) }
    }
}
