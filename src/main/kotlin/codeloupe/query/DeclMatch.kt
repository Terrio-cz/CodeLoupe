package codeloupe.query

import codeloupe.lang.JsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Does a declaration fit the qualifier (`Type.` / `pkg.`) and parameter list a query names? */
internal object DeclMatch {
    /** `Map<K, V>?` -> `Map`. */
    fun baseType(type: String?): String {
        val t = type.orEmpty()
        val generic = t.indexOf('<')
        return JsText.trim((if (generic >= 0) t.substring(0, generic) else t).trimEnd('?', '!'))
    }

    fun qualifier(decl: DeclRow, qualifier: String): Boolean {
        if (qualifier.isEmpty()) return true
        val prefix = decl.fqn.substring(0, maxOf(0, decl.fqn.length - decl.name.length - 1))
        if (prefix == qualifier || prefix.endsWith(".$qualifier")) return true
        val receiver = decl.receiver?.takeIf { it.isNotEmpty() }?.let(::baseType) ?: return false
        return receiver == qualifier || receiver.endsWith(".$qualifier")
    }

    /** `_` matches any parameter type. */
    fun params(decl: DeclRow, wanted: List<String>?): Boolean {
        if (wanted == null) return true
        val actual = Json.parseToJsonElement(decl.params?.takeIf { it.isNotEmpty() } ?: "[]").jsonArray
            .map { it.jsonObject.getValue("type").jsonPrimitive.content }
        if (actual.size != wanted.size) return false
        return wanted.indices.all { i ->
            val w = wanted[i]
            w == "_" || baseType(actual[i]) == baseType(w) || JsText.removeSpaces(actual[i]) == JsText.removeSpaces(w)
        }
    }
}
