package codeloupe.changes

import codeloupe.query.PathOrder
import codeloupe.query.View
import codeloupe.query.usages.UsageFinder

/**
 * `changes`: the declarations a worktree changed against the merge-base with the default branch — `+` added, `~` body
 * changed, `^` signature changed, `-` removed — each with its callers and the tests that use it. The answer grows with
 * the changed declarations, not with the size of the files; files are compared one at a time.
 */
object ChangesQuery {
    data class Args(val bodies: Boolean = false, val limit: Int = 60)

    /** [after] reads the worktree as it is now, [before] the merge-base version of the changed files. */
    fun run(set: ChangeSet, after: View, before: View?, args: Args): String {
        val callers = Callers(UsageFinder(after))
        val counts = LinkedHashMap(MARKS.associateWith { 0 })
        val shown = ArrayList<String>()
        val quiet = ArrayList<String>()
        var listed = 0
        for (file in set.files.sortedWith(compareBy(PathOrder) { it.path })) {
            val old = if (file.status == 'A' || before == null) emptyList() else versions(before, file.path)
            val new = if (file.status == 'D') emptyList() else versions(after, file.path)
            val changes = DeclDiff.of(old, new).sortedBy { it.current.row.startLine }
            if (changes.isEmpty()) {
                quiet += file.path
                continue
            }
            changes.forEach { counts.merge(it.mark, 1, Int::plus) }
            for ((change, nested) in collapse(changes)) {
                if (listed++ >= args.limit) continue
                if (shown.lastOrNull { !it.startsWith(" ") } != heading(file)) shown += heading(file)
                shown += line(change) + note(change, nested)
                details(change, callers).forEach { shown += "      $it" }
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
    private fun collapse(changes: List<DeclChange>): List<Pair<DeclChange, Int>> {
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

    private fun heading(file: ChangedFile) = file.path + when (file.status) {
        'A' -> "  (new)"
        'D' -> "  (deleted)"
        else -> ""
    }

    /** `  ^ 120-140  [Container] signature` with the line range of the current version (the old one when removed). */
    private fun line(change: DeclChange): String {
        val row = change.current.row
        val container = if (row.container.isNotEmpty()) "[${row.container}] " else ""
        return "  ${change.mark} ${row.startLine}-${row.endLine}  $container${row.sig}"
    }

    private fun note(change: DeclChange, nested: Int): String = when {
        nested > 0 -> "  (with $nested ${if (nested == 1) "member" else "members"})"
        change.mark == DeclChange.BODY && change.before!!.sameCode(change.after!!) -> "  (KDoc only)"
        else -> ""
    }

    private fun details(change: DeclChange, callers: Callers): List<String> = buildList {
        if (change.mark == DeclChange.SIGNATURE) add("was: ${change.before!!.row.sig}")
        when {
            change.after == null -> addAll(callers.ofRemoved(change.before!!.row))
            change.mark == DeclChange.SIGNATURE -> addAll(callers.ofChangedSignature(change.after.row, change.before!!.row))
            else -> addAll(callers.of(change.after.row))
        }
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
