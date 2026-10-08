package codeloupe.write

/** Replaces `[start, end)` of a text with [text]; an insertion has `start == end`. */
data class TextEdit(val start: Int, val end: Int, val text: String)
