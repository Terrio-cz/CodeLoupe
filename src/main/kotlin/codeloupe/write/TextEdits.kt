package codeloupe.write

/** Applying edits that do not overlap. */
internal object TextEdits {
    fun apply(text: String, edits: List<TextEdit>): String {
        val sorted = edits.sortedWith(compareBy({ it.start }, { it.end }))
        val out = StringBuilder(text.length + sorted.sumOf { it.text.length })
        var at = 0
        for (edit in sorted) {
            if (edit.start < at || edit.end < edit.start || edit.end > text.length) throw WriteRefused("the edits overlap or lie outside the file; nothing was written")
            out.append(text, at, edit.start).append(edit.text)
            at = edit.end
        }
        return out.append(text, at, text.length).toString()
    }
}
