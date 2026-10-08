package codeloupe.write

import codeloupe.lang.FileFacts

/**
 * Reads a file again after an edit and judges it by what the file declares: syntax errors must not grow, the declarations outside
 * the edited range must be exactly the ones that were there, the range must hold what the edit meant it to hold and must not
 * repeat a declaration the file already has. No compiler is asked; this is the check the daemon can make on its own.
 */
internal object EditVerifier {
    /** The edit replaced `[start, oldEnd)` of the old text by [newLength] characters; [mustDeclare] when the new text has to declare something. */
    class Range(val start: Int, val oldEnd: Int, val newLength: Int, val mustDeclare: Boolean)

    /** Null when the new facts are acceptable, else why not. */
    fun verify(before: FileFacts, after: FileFacts, range: Range): String? {
        if (after.errors > before.errors) return "the file would have ${after.errors - before.errors} more syntax error(s)"
        val newEnd = range.start + range.newLength
        val outsideBefore = outside(before, range.start, range.oldEnd)
        val outsideAfter = outside(after, range.start, newEnd)
        if (outsideBefore.sorted() != outsideAfter.sorted()) {
            val lost = outsideBefore - outsideAfter.toSet()
            val gained = outsideAfter - outsideBefore.toSet()
            return "the edit would change declarations outside its range (${(lost.map { "lost ${show(it)}" } + gained.map { "new ${show(it)}" }).take(3).joinToString("; ")})"
        }
        val inside = after.decls.filter { it.startOffset >= range.start && it.endOffset <= newEnd }.map(DeclKeys::of)
        if (range.mustDeclare && inside.isEmpty()) return "the code declares nothing"
        (inside.firstOrNull { it in outsideAfter } ?: inside.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.key)?.let { return "the file would declare ${show(it)} twice" }
        return null
    }

    /** Null when [after] has no more syntax errors and declares exactly what [before] declared (an edit of imports only). */
    fun verifyDeclarationsUnchanged(before: FileFacts, after: FileFacts): String? {
        if (after.errors > before.errors) return "the file would have ${after.errors - before.errors} more syntax error(s)"
        if (before.decls.map(DeclKeys::of).sorted() != after.decls.map(DeclKeys::of).sorted()) return "the edit would change what the file declares"
        return null
    }

    private fun outside(facts: FileFacts, start: Int, end: Int): List<String> =
        facts.decls.filterNot { it.startOffset >= start && it.endOffset <= end }.map(DeclKeys::of)

    private fun show(key: String): String {
        val (kind, container, name) = key.split('|')
        return "$kind ${if (container.isEmpty()) name else "$container.$name"}"
    }
}
