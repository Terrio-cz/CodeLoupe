package codeloupe.changes

import codeloupe.query.PathOrder
import codeloupe.query.View
import codeloupe.query.usages.UsageFinder

/**
 * `changes`: the declarations a worktree changed against the merge-base with the default branch — `+` added, `~` body
 * changed, `^` signature changed, `-` removed — each with its callers and the tests that use it. The answer grows with
 * the changed declarations, not with the size of the files.
 */
object ChangesQuery {
    data class Args(val bodies: Boolean = false, val limit: Int = 60)

    /** [after] reads the worktree as it is now, [before] the merge-base version of the changed files. */
    fun run(set: ChangeSet, after: View, before: View?, args: Args): String {
        val callers = Callers(UsageFinder(after))
        val files = set.files.sortedWith(compareBy(PathOrder) { it.path }).map { it to changes(it, after, before) }
        val all = files.flatMap { it.second }
        val visible = files.sumOf { collapse(it.second).size }
        val shown = ArrayList<String>()
        var listed = 0
        for ((file, changes) in files) {
            if (changes.isEmpty()) continue
            shown += "${file.path}${if (file.status == 'A') "  (new)" else if (file.status == 'D') "  (deleted)" else ""}"
            for ((change, nested) in collapse(changes)) {
                if (listed++ >= args.limit) continue
                shown += line(change) + if (nested > 0) "  (with $nested ${if (nested == 1) "member" else "members"})" else ""
                details(change, callers).forEach { shown += "      $it" }
                if (args.bodies && change.before != null && change.after != null) shown += TextDiff.of(change.before.text, change.after.text)
            }
        }
        val quiet = files.filter { it.second.isEmpty() }.map { it.first.path }
        return buildString {
            append(header(set, all))
            if (shown.isNotEmpty()) append('\n').append(shown.joinToString("\n"))
            if (visible > args.limit) append("\n… +${visible - args.limit} more declarations (raise limit)")
            if (quiet.isNotEmpty()) append("\nno declaration changed (imports, comments, formatting): ${quiet.joinToString(", ")}")
            if (set.otherFiles.isNotEmpty()) {
                append("\nother changed files: ${set.otherFiles.take(OTHER_FILES).joinToString(", ")}")
                if (set.otherFiles.size > OTHER_FILES) append(", … +${set.otherFiles.size - OTHER_FILES}")
            }
        }
    }

    private fun changes(file: ChangedFile, after: View, before: View?): List<DeclChange> {
        val old = if (file.status == 'A' || before == null) emptyList() else versions(before, file.path)
        val new = if (file.status == 'D') emptyList() else versions(after, file.path)
        return DeclDiff.of(old, new).sortedBy { it.current.row.startLine }
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

    private fun header(set: ChangeSet, all: List<DeclChange>): String {
        val counts = listOf(DeclChange.ADDED, DeclChange.BODY, DeclChange.SIGNATURE, DeclChange.REMOVED)
            .mapNotNull { mark -> all.count { it.mark == mark }.takeIf { it > 0 }?.let { "$mark$it" } }
        val summary = if (all.isEmpty()) "no declaration changed" else "${all.size} declarations (${counts.joinToString(" ")})"
        return "changes vs ${set.defaultRef} (merge-base ${set.mergeBase.take(7)}): ${set.files.size} source files, $summary"
    }

    /** `  ^ 120-140  [Container] signature` with the line range of the current version (the old one when removed). */
    private fun line(change: DeclChange): String {
        val row = change.current.row
        val container = if (row.container.isNotEmpty()) "[${row.container}] " else ""
        return "  ${change.mark} ${row.startLine}-${row.endLine}  $container${row.sig}"
    }

    private fun details(change: DeclChange, callers: Callers): List<String> = buildList {
        if (change.mark == DeclChange.SIGNATURE) add("was: ${change.before!!.row.sig}")
        addAll(if (change.after == null) callers.ofRemoved(change.before!!.row) else callers.of(change.after.row))
    }

    private const val OTHER_FILES = 15
}
