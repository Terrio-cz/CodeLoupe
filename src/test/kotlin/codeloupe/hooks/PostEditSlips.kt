package codeloupe.hooks

/** A typical slip of an agent, put into the text of a clean Kotlin file: [change] gives the spoilt text and the name the slip is about, or null when the file has no place for it. */
class PostEditSlips private constructor(val what: String, private val change: (String) -> Pair<String, String>?, private val named: (String, String) -> Boolean) {
    fun applyTo(text: String): Pair<String, String>? = change(text)

    /** True when the [answer] of the check is about the slip made with [name]. */
    fun seenIn(name: String, answer: String): Boolean = named(name, answer)

    companion object {
        private val SKIPPED_WORDS = setOf("if", "when", "for", "while", "catch", "fun", "return", "assert", "super", "this", "class")

        val ALL = listOf(
            PostEditSlips("a missing closing brace", ::withoutLastBrace) { _, answer -> "syntax error" in answer },
            PostEditSlips("a removed import of a type the file uses", ::withoutImport) { name, answer -> "`$name` is not imported" in answer },
            PostEditSlips("an invented function name", ::withInventedCall) { name, answer -> "unknown function `$name`" in answer || "`$name` is not imported" in answer },
        )

        private fun withoutLastBrace(text: String): Pair<String, String>? =
            text.lastIndexOf('}').takeIf { it >= 0 }?.let { text.removeRange(it, it + 1) to "" }

        // An import of a type from the file's own top-level package that the file goes on to use.
        private fun withoutImport(text: String): Pair<String, String>? {
            val own = Regex("""^package (\w+\.\w+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return null
            val imports = Regex("""^import (${Regex.escape(own)}[\w.]*\.([A-Z]\w*))\r?$""", RegexOption.MULTILINE).findAll(text)
            val dropped = imports.firstOrNull { Regex("""\b${it.groupValues[2]}\b""").findAll(text).count() > 1 } ?: return null
            return text.removeRange(dropped.range.first, dropped.range.last + 1) to dropped.groupValues[2]
        }

        // The first plain call in the file, spelt a little differently.
        private fun withInventedCall(text: String): Pair<String, String>? {
            var offset = 0
            for (line in text.split("\n")) {
                val code = line.trim()
                val plain = listOf("fun ", "*", "/", "import", "@", "package").none { code.startsWith(it) } && '"' !in code
                val call = if (plain) Regex("""(?<![\w.`])([a-z]\w{3,})\(""").find(line) else null
                if (call != null && call.groupValues[1] !in SKIPPED_WORDS) {
                    val at = offset + call.groups[1]!!.range.last + 1
                    return text.substring(0, at) + "Zz" + text.substring(at) to call.groupValues[1] + "Zz"
                }
                offset += line.length + 1
            }
            return null
        }
    }
}
