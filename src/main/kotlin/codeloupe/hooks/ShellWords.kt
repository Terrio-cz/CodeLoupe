package codeloupe.hooks

/**
 * Splits a shell line (Bash or PowerShell) into its simple commands, as far as the hook needs: operators `| || && ; &` and
 * newlines end a command, quotes group words, redirections and here-document bodies are dropped, `$(...)` and
 * backticks stay inside their word. It does not expand anything.
 */
object ShellWords {
    private val OUTPUT_ALONE = Regex("""^(\d*|&)>>?$""")
    private val INPUT_ALONE = Regex("""^\d*<$""")
    private val DUPLICATE = Regex("""^\d*>>?&(\d+-?|-)$""")
    private val OUTPUT_INLINE = Regex("""^(\d*|&)>>?(?!&)(.+)$""")
    private val INPUT_INLINE = Regex("""^\d*<(?![<(])(.+)$""")

    fun split(line: String): List<SimpleCommand> = Parser(line).apply { run() }.commands

    private class Parser(private val text: String) {
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
                    c == '\\' && i + 1 < text.length && isEscapable(text[i + 1]) -> { open(false); word.append(text[i + 1]); i += 2 }
                    c == '\'' -> single()
                    c == '"' -> double()
                    c == '`' -> backtick()
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
            while (i < text.length && text[i] != '\'') word.append(text[i++])
            i++
        }

        private fun double() {
            open(true)
            i++
            while (i < text.length && text[i] != '"') {
                if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in "\"\\$`") i++
                word.append(text[i++])
            }
            i++
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
