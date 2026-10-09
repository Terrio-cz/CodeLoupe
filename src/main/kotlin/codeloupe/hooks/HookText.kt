package codeloupe.hooks

/**
 * Text that comes from a repository (branch names, directory names, file paths) and is handed to an agent as context. Control characters
 * and the Unicode controls that reorder or hide text are dropped, and a line is cut at a length no real name reaches, so a name cannot
 * carry a line break, an escape sequence or a hidden instruction into the session.
 */
object HookText {
    private const val MAX_LINE = 300

    /** One name on one line. */
    fun line(text: String, max: Int = MAX_LINE): String = block(text, max).replace('\n', ' ')

    /** A listing: lines kept, everything that is not printable text dropped, long lines cut. */
    fun block(text: String, maxLine: Int = MAX_LINE): String =
        text.lineSequence().joinToString("\n") { cut(it.filterNot(::hidden), maxLine) }

    private fun hidden(c: Char): Boolean = c.isISOControl() && c != '\t' || c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '﻿'

    private fun cut(line: String, max: Int): String = if (line.length <= max) line else line.take(max - 1) + "…"
}
