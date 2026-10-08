package codeloupe.secrets.imports

/**
 * Reads `KEY=value` statements of a dotenv file: `export` prefix, `#` comments, an inline ` #` comment after an unquoted
 * value, single and double quotes (a quoted value may span lines, which is how a PEM key is kept). The offsets say which
 * text a replacement overwrites: from the key to the end of the value, line ending excluded.
 */
object DotenvParser {
    class Entry(val name: String, val value: String, val line: Int, val start: Int, val end: Int) {
        override fun toString() = "$name (line $line)"
    }

    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var pos = if (text.startsWith("﻿")) 1 else 0
        var line = 1
        while (pos < text.length) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val entry = statement(text, pos, lineEnd, line)
            if (entry != null) {
                entries += entry
                line += text.substring(pos, entry.end).count { it == '\n' }
                pos = text.indexOf('\n', entry.end).let { if (it < 0) text.length else it + 1 }
            } else {
                pos = lineEnd + 1
            }
            line += 1
        }
        return entries
    }

    private fun statement(text: String, lineStart: Int, lineEnd: Int, line: Int): Entry? {
        var i = lineStart
        while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= lineEnd || text[i] == '#') return null
        val start = i
        if (text.startsWith("export", i) && i + 6 < lineEnd && (text[i + 6] == ' ' || text[i + 6] == '\t')) {
            i += 6
            while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
        }
        val nameStart = i
        while (i < lineEnd && text[i] != '=' && !text[i].isWhitespace()) i++
        val name = text.substring(nameStart, i)
        while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (name.isEmpty() || i >= lineEnd || text[i] != '=') return null
        i++
        while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
        val valueStart = i
        val quote = text.getOrNull(i)?.takeIf { (it == '"' || it == '\'') && i < lineEnd }
        if (quote != null) {
            val close = closingQuote(text, i + 1, quote)
            if (close >= 0) {
                val raw = text.substring(i + 1, close)
                return Entry(name, if (quote == '"') unescape(raw) else raw, line, start, close + 1)
            }
        }
        var end = lineEnd
        if (end > valueStart && text[end - 1] == '\r') end--
        val comment = Regex("\\s#").find(text.substring(valueStart, end))
        if (comment != null) end = valueStart + comment.range.first
        val value = text.substring(valueStart, end).trimEnd()
        return Entry(name, value, line, start, valueStart + value.length)
    }

    private fun closingQuote(text: String, from: Int, quote: Char): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (quote == '"' && c == '\\') i++ else if (c == quote) return i
            i++
        }
        return -1
    }

    private fun unescape(raw: String): String {
        if ('\\' !in raw) return raw
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '"', '\\' -> out.append(next)
                    else -> out.append(c).append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
