package codeloupe.changes

import codeloupe.query.Format
import codeloupe.query.PathOrder
import codeloupe.query.SigText
import codeloupe.query.View
import codeloupe.query.usages.UsageFinder

/**
 * `changes`: the declarations a worktree changed against the merge-base with the default branch — `+` added, `~` body
 * changed, `^` signature changed, `-` removed — each with its callers and the tests that use it. The answer grows with
 * the changed declarations, not with the size of the files; files are compared one at a time.
 */
object ChangesQuery {
    data class Args(val bodies: Boolean = false, val limit: Int = 60, val callers: Boolean = false, val tests: Boolean = false)

    /** [after] reads the worktree as it is now, [before] the merge-base version of the changed files. */
    fun run(set: ChangeSet, after: View, before: View?, args: Args): String {
        if (args.tests) return TestSelection.run(set, after, before)
        val callers = Callers(UsageFinder(after))
        val counts = LinkedHashMap(MARKS.associateWith { 0 })
        val shown = ArrayList<String>()
        val quiet = ArrayList<String>()
        var listed = 0
        var previousDir = ""
        var headed = ""
        for (file in set.files.sortedWith(compareBy(PathOrder) { it.path })) {
            val (old, new) = versions(file, after, before)
            val changes = DeclDiff.of(old, new).sortedBy { it.current.row.startLine }
            if (changes.isEmpty()) {
                quiet += file.path
                continue
            }
            changes.forEach { counts.merge(it.mark, 1, Int::plus) }
            for ((change, nested) in collapse(changes)) {
                if (listed++ >= args.limit) continue
                if (headed != file.path) {
                    shown += heading(file, previousDir)
                    headed = file.path
                    previousDir = file.path.substringBeforeLast('/', "")
                }
                val (sig, was) = signatures(change)
                shown += line(change, sig) + note(change, nested)
                if (was != null) shown += "      was: $was"
                // An added declaration has no callers yet that the change did not bring; `callers` asks for them anyway.
                if (change.mark != DeclChange.ADDED || args.callers) callerLines(change, callers).forEach { shown += "      $it" }
                if (args.bodies) body(change, old, new)?.let { shown += it }
            }
        }
        return buildString {
            append(header(set, counts))
            if (shown.isNotEmpty()) append('\n').append(shown.joinToString("\n"))
            if (listed > args.limit) append("\n… +${listed - args.limit} more declarations (raise limit)")
            if (quiet.isNotEmpty()) append("\nno declaration changed (imports, comments, formatting): ").append(capped(quiet))
            if (set.otherFiles.isNotEmpty()) append("\nother changed files: ").append(capped(set.otherFiles))
        }
    }

    /**
     * The changes to list, each with the number of nested ones it stands for: the members of an added (removed) type
     * that were added (removed) with it are part of it, not news of their own.
     */
    internal fun collapse(changes: List<DeclChange>): List<Pair<DeclChange, Int>> {
        val byRow = changes.filter { it.mark == DeclChange.ADDED || it.mark == DeclChange.REMOVED }.associateBy { it.mark to it.current.row.id }
        val nested = HashMap<DeclChange, Int>()
        val hidden = HashSet<DeclChange>()
        for (change in byRow.values) {
            var outer: DeclChange? = null
            var parent = change.current.row.parentId
            while (parent != null) {
                outer = byRow[change.mark to parent] ?: break
                parent = outer.current.row.parentId
            }
            if (outer != null) {
                hidden += change
                nested.merge(outer, 1, Int::plus)
            }
        }
        return changes.filter { it !in hidden }.map { it to (nested[it] ?: 0) }
    }

    /** The declarations of [file] before the change and now. */
    internal fun versions(file: ChangedFile, after: View, before: View?): Pair<List<DeclVersion>, List<DeclVersion>> {
        val old = if (file.status == 'A' || before == null) emptyList() else versions(before, file.path)
        val new = if (file.status == 'D') emptyList() else versions(after, file.path)
        return old to new
    }

    private fun versions(view: View, path: String): List<DeclVersion> {
        val content = view.file(path)?.content ?: return emptyList()
        return DeclVersion.of(view.decls("f.path = :path", mapOf("path" to path), "ORDER BY start_line"), content)
    }

    private fun header(set: ChangeSet, counts: Map<Char, Int>): String {
        val total = counts.values.sum()
        val marks = counts.filterValues { it > 0 }.entries.joinToString(" ") { (mark, n) -> "$mark$n" }
        val summary = if (total == 0) "no declaration changed" else "$total declarations ($marks)"
        return "changes vs ${set.defaultRef} (merge-base ${set.mergeBase.take(7)}): ${set.files.size} source files, $summary"
    }

    /** The path, as `./name` when the file above it is in the same directory. */
    private fun heading(file: ChangedFile, previousDir: String): String {
        val dir = file.path.substringBeforeLast('/', "")
        val name = if (dir.isNotEmpty() && dir == previousDir) "./" + file.path.substringAfterLast('/') else file.path
        return name + status(file)
    }

    private fun status(file: ChangedFile) = when (file.status) {
        'A' -> "  (new)"
        'D' -> "  (deleted)"
        else -> ""
    }

    /** `  ^ 120-140  [Container] signature` with the line range of the current version (the old one when removed). */
    private fun line(change: DeclChange, sig: String): String {
        val row = change.current.row
        val container = if (row.container.isNotEmpty()) "[${row.container}] " else ""
        return "  ${change.mark} ${Format.range(row)}  $container$sig"
    }

    /** The signature to print and, for a changed one, the old one beside it: whole when short, else around the difference. */
    private fun signatures(change: DeclChange): Pair<String, String?> {
        val sig = SigText.plain(change.current.row.sig)
        val old = change.before?.row?.sig?.let(SigText::plain)
        if (change.mark != DeclChange.SIGNATURE || old == null) return SigWindow.clip(sig) to null
        return SigWindow.around(sig, old)
    }

    private fun note(change: DeclChange, nested: Int): String = when {
        nested > 0 -> "  (with $nested ${if (nested == 1) "member" else "members"})"
        change.mark == DeclChange.BODY && change.before!!.sameCode(change.after!!) -> "  (KDoc only)"
        else -> ""
    }

    private fun callerLines(change: DeclChange, callers: Callers): List<String> = when {
        change.after == null -> callers.ofRemoved(change.before!!.row)
        change.mark == DeclChange.SIGNATURE -> callers.ofChangedSignature(change.after.row, change.before!!.row)
        else -> callers.of(change.after.row)
    }

    // A type's own lines only: its members' changes are listed, and diffed, on their own.
    private fun body(change: DeclChange, before: List<DeclVersion>, after: List<DeclVersion>): String? {
        val (old, new) = (change.before ?: return null) to (change.after ?: return null)
        if (!new.isType) return TextDiff.of(old.text, new.text)
        return TextDiff.of(old.ownText(before), new.ownText(after))
    }

    private fun capped(paths: List<String>) =
        paths.take(LISTED_FILES).joinToString(", ") + if (paths.size > LISTED_FILES) ", … +${paths.size - LISTED_FILES}" else ""

    private val MARKS = listOf(DeclChange.ADDED, DeclChange.BODY, DeclChange.SIGNATURE, DeclChange.REMOVED)
    private const val LISTED_FILES = 15
}
