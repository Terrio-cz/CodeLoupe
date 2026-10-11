package codeloupe.brain

import codeloupe.query.DeclMatch
import codeloupe.query.DeclRow
import codeloupe.query.View
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The stable id of a declaration as a brain entry anchors it: `@sym:<fully qualified name>` (with `(ParamType,...)` only where the name is
 * overloaded), `@file:<path>` and `@mod:<module>`. Never a line: lines move, names, files and modules are what a rename or a move changes, and the
 * index follows those (see [BrainAnchorCheck]). The qualified name resolves with the same notation `symbol` takes.
 */
object DeclAnchors {
    private val NO_SIGNATURE = setOf("class", "interface", "object", "enum", "annotation", "typealias", "companion", "property", "enum_entry")

    fun of(view: View, decl: DeclRow): List<String> = listOfNotNull(
        sym(view, decl),
        "${IndexedEntry.FILE}${decl.path}",
        decl.module.takeIf { it.isNotEmpty() }?.let { "${IndexedEntry.MOD}$it" },
    )

    fun sym(view: View, decl: DeclRow): String {
        val base = "${IndexedEntry.SYM}${decl.fqn}"
        if (decl.kind in NO_SIGNATURE) return base
        val same = view.decls("d.fqn = :fqn AND d.local = 0 AND d.kind = :kind", mapOf("fqn" to decl.fqn, "kind" to decl.kind))
        return if (same.size > 1) "$base(${parameterTypes(decl).joinToString(",")})" else base
    }

    /** Base types of the parameters (`Map<K, V>?` -> `Map`), what a signature anchor compares. */
    fun parameterTypes(decl: DeclRow): List<String> = Json.parseToJsonElement(decl.params?.takeIf { it.isNotEmpty() } ?: "[]").jsonArray
        .map { DeclMatch.baseType(it.jsonObject.getValue("type").jsonPrimitive.content).replace(" ", "") }

    /** The smallest non-local declaration whose lines hold [line] of [path], or null for a line between declarations. */
    fun enclosing(view: View, path: String, line: Int): DeclRow? =
        view.decls("f.path = :path AND d.local = 0 AND d.start_line <= :line AND d.end_line >= :line", mapOf("path" to path, "line" to line), "ORDER BY (end_line - start_line) ASC LIMIT 1").firstOrNull()
}
