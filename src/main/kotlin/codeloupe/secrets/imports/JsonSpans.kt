package codeloupe.secrets.imports

/**
 * A JSON reader that keeps where every value sits in the text, so a string can be replaced in place and the rest of the
 * file (a Claude Code settings file, `~/.claude.json`) stays byte for byte as its owner wrote it. A malformed document
 * gives null; no message ever quotes the text.
 */
object JsonSpans {
    sealed class Node(val start: Int, val end: Int)

    class Obj(start: Int, end: Int, val members: List<Member>) : Node(start, end)

    data class Member(val key: String, val value: Node)

    class Arr(start: Int, end: Int, val items: List<Node>) : Node(start, end)

    class Str(start: Int, end: Int) : Node(start, end) {
        fun value(text: String): String = decode(text, start + 1, end - 1)
    }

    class Other(start: Int, end: Int) : Node(start, end)

    private const val MAX_DEPTH = 100

    fun parse(text: String): Node? = try {
        Reader(text).run { document() }
    } catch (e: IllegalStateException) {
        null
    }

    private class Reader(val text: String) {
        var pos = 0

        fun document(): Node {
            skip()
            val node = value(0)
            skip()
            check(pos == text.length) { "trailing text" }
            return node
        }

        fun value(depth: Int): Node {
            check(depth < MAX_DEPTH) { "too deep" }
            skip()
            check(pos < text.length) { "unexpected end" }
            return when (text[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                else -> other()
            }
        }

        fun obj(depth: Int): Obj {
            val start = pos++
            val members = mutableListOf<Member>()
            skip()
            if (peek() == '}') return Obj(start, ++pos, members)
            while (true) {
                skip()
                check(peek() == '"') { "key expected" }
                val key = str().let { it.value(text) }
                skip()
                check(peek() == ':') { "colon expected" }
                pos++
                members += Member(key, value(depth + 1))
                skip()
                when (peek()) {
                    ',' -> pos++
                    '}' -> return Obj(start, ++pos, members)
                    else -> error("comma expected")
                }
            }
        }

        fun arr(depth: Int): Arr {
            val start = pos++
            val items = mutableListOf<Node>()
            skip()
            if (peek() == ']') return Arr(start, ++pos, items)
            while (true) {
                items += value(depth + 1)
                skip()
                when (peek()) {
                    ',' -> pos++
                    ']' -> return Arr(start, ++pos, items)
                    else -> error("comma expected")
                }
            }
        }

        fun str(): Str {
            val start = pos++
            while (pos < text.length) {
                when (text[pos]) {
                    '\\' -> pos += 2
                    '"' -> return Str(start, ++pos)
                    else -> pos++
                }
            }
            error("unterminated string")
        }

        fun other(): Other {
            val start = pos
            while (pos < text.length && text[pos] !in ",}] \t\r\n") pos++
            check(pos > start) { "value expected" }
            return Other(start, pos)
        }

        fun peek(): Char = if (pos < text.length) text[pos] else error("unexpected end")

        fun skip() {
            if (pos == 0 && text.startsWith("﻿")) pos = 1
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }

    private fun decode(text: String, from: Int, to: Int): String {
        if (text.indexOf('\\', from).let { it < 0 || it >= to }) return text.substring(from, to)
        val out = StringBuilder()
        var i = from
        while (i < to) {
            val c = text[i]
            if (c != '\\' || i + 1 >= to) {
                out.append(c)
                i++
                continue
            }
            when (val e = text[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val code = text.substring(i + 2, minOf(i + 6, to)).toIntOrNull(16)
                    if (code != null && i + 6 <= to) {
                        out.append(code.toChar())
                        i += 4
                    } else out.append(e)
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }
}
