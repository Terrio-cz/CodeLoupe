package codeloupe.metrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The length JavaScript's `JSON.stringify(value).length` has, without building the text: the workspace script sizes the
 * attachments of a starting context that way, and the figures of both tools have to agree. Strings count UTF-16 units; the
 * quote, the backslash, `\b \f \n \r \t` take two, other control characters and lone surrogates six.
 */
internal object JsStringify {
    fun length(element: JsonElement): Long = when (element) {
        is JsonNull -> NULL.toLong()
        is JsonObject -> 2L + element.entries.sumOf { (k, v) -> text(k) + 1 + length(v) } + maxOf(0, element.size - 1)
        is JsonArray -> 2L + element.sumOf(::length) + maxOf(0, element.size - 1)
        is JsonPrimitive -> if (element.isString) text(element.content) else scalar(element.content)
    }

    private fun scalar(literal: String): Long = when (literal) {
        "true", "null" -> 4
        "false" -> 5
        else -> number(literal).length.toLong()
    }

    private fun text(s: String): Long {
        var n = 2L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c == '"' || c == '\\' || c == '\b' || c == '\u000C' || c == '\n' || c == '\r' || c == '\t' -> 2
                c < ' ' -> 6
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 2 }
                Character.isSurrogate(c) -> 6
                else -> 1
            }
            i++
        }
        return n
    }

    /** JavaScript's text of a number read from a JSON literal: integers and fractions plain, an exponent only beyond 1e21 or below 1e-6. */
    private fun number(literal: String): String {
        val d = literal.toDoubleOrNull() ?: return "null"
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == 0.0) return "0"
        val abs = Math.abs(d)
        if (abs >= 1e21 || abs < 1e-6) return exponent(d)
        return BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
    }

    private fun exponent(d: Double): String {
        val digits = BigDecimal(d.toString()).stripTrailingZeros()
        val exp = digits.precision() - digits.scale() - 1
        val mantissa = digits.unscaledValue().abs().toString()
        val body = if (mantissa.length == 1) mantissa else mantissa[0] + "." + mantissa.substring(1)
        return (if (d < 0) "-" else "") + body + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp)
    }

    private const val NULL = 4
}
