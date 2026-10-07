package codeloupe.query.usages

import codeloupe.query.DeclRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A call's candidates narrowed by argument count: functions whose parameters fit (defaults and `vararg` make a
 * range); `x()` on a property (`invoke`) only when no function of that name fits.
 */
internal object Arguments {
    fun narrow(r: Resolution, args: Int?): Resolution {
        val functions = r.decls.filter { it.kind == "fun" }
        if (functions.isEmpty()) return r
        val fitting = if (args == null || args < 0) functions else functions.filter { fits(it, args) }
        return when {
            fitting.isNotEmpty() -> r.filter { it.kind != "property" && (it.kind != "fun" || it in fitting) }
            r.decls.any { it.kind != "fun" } -> r.filter { it.kind != "fun" }
            else -> r
        }
    }

    fun fits(d: DeclRow, args: Int): Boolean {
        val params = Json.parseToJsonElement(d.params?.takeIf { it.isNotEmpty() } ?: "[]").jsonArray.map { it.jsonObject }
        val required = params.count { p -> p["default"]?.jsonPrimitive?.booleanOrNull != true && p["vararg"]?.jsonPrimitive?.booleanOrNull != true }
        val vararg = params.any { it["vararg"]?.jsonPrimitive?.booleanOrNull == true }
        return args >= required && (vararg || args <= params.size)
    }
}
