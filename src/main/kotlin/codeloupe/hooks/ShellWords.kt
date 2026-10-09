package codeloupe.hooks

/**
 * Splits a shell line (Bash or PowerShell) into its simple commands, as far as the hook needs: operators `| || && ; &` and
 * newlines end a command, quotes group words, redirections and here-document bodies are dropped, `$(...)` and
 * backticks stay inside their word. It does not expand anything.
 *
 * PowerShell differs where it matters for that: a backslash is a path separator, never an escape; the backtick escapes a
 * character or continues the line; `''` and `""` inside a string are a quote; `&` calls a command; a parenthesised
 * expression is one word, so `(Get-Content a.kt) -replace 'x','y' | Set-Content a.kt` stays one pipeline; `@'…'@` is a string.
 */
object ShellWords {
    private val OUTPUT_ALONE = Regex("""^(\d*|&)>>?$""")
    private val INPUT_ALONE = Regex("""^\d*<$""")
    private val DUPLICATE = Regex("""^\d*>>?&(\d+-?|-)$""")
    private val OUTPUT_INLINE = Regex("""^(\d*|&)>>?(?!&)(.+)$""")
    private val INPUT_INLINE = Regex("""^\d*<(?![<(])(.+)$""")

    // Keywords whose parenthesis holds a condition, not an operand: the commands after it are separate statements.
    private val CONTROL = setOf("if", "elseif", "while", "until", "for", "foreach", "switch", "catch", "function", "filter")

    fun split(line: String, dialect: ShellDialect = ShellDialect.POSIX): List<SimpleCommand> =
        Parser(line, dialect == ShellDialect.POWERSHELL).apply { run() }.commands

    private class Parser(private val text: String, private val ps: Boolean) {
        val commands = ArrayList<SimpleCommand>()
        private var words = ArrayList<String>()
        private var word = StringBuilder()
        private var inWord = false
        private var startedQuoted = false
        private var piped = false
        private var writes = false
        private var skipNext = false
        private var delimiterNext = false
        private val heredocs = ArrayList<String>()
        private var i = 0

        fun run() {
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && !ps && i + 1 < text.length && isEscapable(text[i + 1]) -> { open(false); word.append(text[i + 1]); i += 2 }
                    c == '\'' -> single()
                    c == '"' -> double()
                    c == '`' && ps -> escape()
                    c == '`' -> backtick()
                    c == '@' && ps && !inWord && (text.startsWith("@'", i) || text.startsWith("@\"", i)) -> hereString()
                    c == '(' && ps && groups() -> group()
                    c == '$' && text.startsWith("$(", i) -> substitution()
                    c == '\n' -> { endCommand(); i++; skipHeredocBodies() }
                    c == ';' -> { endCommand(); i++ }
                    c == '|' -> pipe()
                    c == '&' -> ampersand()
                    c == '(' || c == ')' -> { endWord(); if (c == ')') endCommand(); i++ }
                    c == ' ' || c == '\t' || c == '\r' -> { endWord(); i++ }
                    c == '#' && !inWord -> { while (i < text.length && text[i] != '\n') i++ }
                    else -> { open(false); word.append(c); i++ }
                }
            }
            endCommand()
        }

        private fun open(quoted: Boolean) {
            if (!inWord) startedQuoted = quoted
            inWord = true
        }

        // A backslash is a path separator unless it protects a character the shell treats specially.
        private fun isEscapable(next: Char) = next in " \"'$\\`|&;()"

        private fun single() {
            open(true)
            i++
            while (i < text.length) {
                if (text[i] == '\'') {
                    if (!(ps && text.startsWith("''", i))) break
                    i++
                }
                word.append(text[i++])
            }
            i++
        }

        private fun double() {
            open(true)
            i++
            while (i < text.length) {
                val c = text[i]
                if (c == '"') {
                    if (!(ps && text.startsWith("\"\"", i))) break
                    i++
                } else if (ps && c == '`' && i + 1 < text.length) {
                    i++
                } else if (!ps && c == '\\' && i + 1 < text.length && text[i + 1] in "\"\\$`") {
                    i++
                }
                word.append(text[i++])
            }
            i++
        }

        // PowerShell: a backtick ends a line (and is dropped with it) or makes the next character literal.
        private fun escape() {
            val next = text.getOrNull(i + 1)
            when {
                next == null -> i++
                next == '\n' -> i += 2
                next == '\r' -> i += if (text.startsWith("\r\n", i + 1)) 3 else 2
                else -> { open(false); word.append(next); i += 2 }
            }
        }

        // `@'` … `'@` and `@"` … `"@`: everything between is one string, however many lines it has.
        private fun hereString() {
            val close = "${text[i + 1]}@"
            open(true)
            val start = text.indexOf('\n', i).let { if (it < 0) text.length else it + 1 }
            val end = text.indexOf("\n$close", start - 1).let { if (it < 0) text.length else it }
            word.append(text.substring(minOf(start, end), end).removeSuffix("\r"))
            i = minOf(text.length, end + 1 + close.length)
        }

        private fun groups(): Boolean = words.firstOrNull()?.lowercase() !in CONTROL

        // PowerShell: `( … )` is one operand, whatever it holds; quotes inside may hide a parenthesis.
        private fun group() {
            open(true)
            val start = i
            var depth = 0
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                    '\'', '"' -> {
                        val quote = text[i]
                        i++
                        while (i < text.length && text[i] != quote) i++
                    }
                }
                i++
                if (depth == 0) break
            }
            word.append(text, start, minOf(i, text.length))
        }

        private fun backtick() {
            open(true)
            val end = text.indexOf('`', i + 1).let { if (it < 0) text.length else it + 1 }
            word.append(text, i, end)
            i = end
        }

        private fun substitution() {
            open(true)
            var depth = 0
            val start = i
            while (i < text.length) {
                if (text[i] == '(') depth++
                if (text[i] == ')' && --depth == 0) { i++; break }
                i++
            }
            word.append(text, start, i)
        }

        private fun pipe() {
            val double = text.startsWith("||", i)
            endCommand()
            piped = !double
            i += if (double || text.startsWith("|&", i)) 2 else 1
        }

        private fun ampersand() {
            // `2>&1` and `&>file`: the ampersand belongs to the redirection word being read.
            val redirecting = (inWord && word.isNotEmpty() && word.last() == '>' && !startedQuoted) || (!inWord && text.startsWith("&>", i))
            if (redirecting) {
                open(false)
                word.append('&')
                i++
                return
            }
            // PowerShell's call operator: `& "C:\tools\rg.exe" foo` runs what follows.
            if (ps && !inWord && words.isEmpty() && !text.startsWith("&&", i)) {
                i++
                return
            }
            endCommand()
            i += if (text.startsWith("&&", i)) 2 else 1
        }

        private fun endWord() {
            if (!inWord) return
            val value = word.toString()
            word = StringBuilder()
            inWord = false
            when {
                skipNext -> skipNext = false
                delimiterNext -> { delimiterNext = false; heredocs += value }
                startedQuoted -> words += value
                value.startsWith("<<<") -> { writes = true; if (value.length == 3) skipNext = true }
                value.startsWith("<<") -> {
                    writes = true
                    val rest = value.removePrefix("<<").removePrefix("-")
                    if (rest.isEmpty()) delimiterNext = true else heredocs += rest
                }
                DUPLICATE.matches(value) -> Unit
                OUTPUT_ALONE.matches(value) -> { writes = true; skipNext = true }
                INPUT_ALONE.matches(value) -> skipNext = true
                OUTPUT_INLINE.matches(value) -> writes = writes || !value.endsWith("/dev/null")
                INPUT_INLINE.matches(value) -> Unit
                else -> words += value
            }
        }

        private fun endCommand() {
            endWord()
            skipNext = false
            delimiterNext = false
            if (words.isNotEmpty()) commands += SimpleCommand(words, piped, writes)
            words = ArrayList()
            piped = false
            writes = false
        }

        private fun skipHeredocBodies() {
            for (delimiter in heredocs) {
                while (i < text.length) {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    val line = text.substring(i, end)
                    i = minOf(end + 1, text.length)
                    if (line.trim() == delimiter) break
                }
            }
            heredocs.clear()
        }
    }
}
