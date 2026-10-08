package codeloupe.taskcode

import codeloupe.changes.DeclChange
import codeloupe.query.PathOrder
import codeloupe.tracker.Times
import codeloupe.tracker.read.TaskRow

/** The compact text of `task_code`: a task's code, or a declaration's or file's tasks. */
object TaskCodeRender {
    class TaskAnswer(
        val task: String,
        val row: TaskRow?,
        val landed: LandedTask,
        val files: Map<String, Char>,
        /** Null when a work commit was too large to parse; absent (empty list) when nothing declared changed. */
        val decls: List<CommitDecl>?,
        val worktree: Worktree?,
        val prediction: TouchPrediction.Result?,
        val note: String?,
    )

    class Worktree(val branch: String, val path: String, val files: List<String>, val otherFiles: List<String>)

    class TaskOnCode(val task: String, val row: TaskRow?, val landing: TaskCommit, val marks: List<Char>, val fileOnly: Boolean)

    class OpenOnCode(val task: String, val row: TaskRow?, val prediction: Prediction)

    fun task(a: TaskAnswer, limit: Int): String = buildList {
        add(a.row?.line() ?: a.task)
        a.note?.let { add(it) }
        if (a.landed.commits.isNotEmpty()) addAll(landed(a, limit))
        if (a.landed.commits.isEmpty() && a.landed.mentionedOnly.isNotEmpty()) {
            add("not landed; mentioned by ${a.landed.mentionedOnly.size} merge commits (" + a.landed.mentionedOnly.takeLast(3).joinToString(", ") { it.short } + ")")
        }
        a.worktree?.let { wt ->
            val count = wt.files.size + wt.otherFiles.size
            add("in progress: ${wt.path} (branch ${wt.branch}), $count files changed against the merge-base" + if (count == 0) "" else ": " + capped(wt.files + wt.otherFiles, WORKTREE_FILES))
        }
        a.prediction?.let { addAll(predicted(it, limit)) }
        if (a.landed.commits.isEmpty() && a.worktree == null && a.prediction == null) add("no commits of ${a.task} on the default branch" + if (a.row == null) " and no mirror of its project" else "")
    }.joinToString("\n")

    private fun landed(a: TaskAnswer, limit: Int): List<String> = buildList {
        val landing = a.landed.landing!!
        val how = when {
            landing.merge && a.landed.commits.size > 1 -> "merge of ${plural(a.landed.commits.size - a.landed.work.size, "commit")}"
            landing.merge -> "merge"
            a.landed.commits.size > 1 -> "newest of ${a.landed.commits.size} commits"
            else -> "1 commit"
        }
        val counts = a.decls?.let { counts(it) }
        val declText = when {
            a.decls == null -> "declarations not listed (a commit too large to parse here)"
            a.decls.isEmpty() -> "no declaration changed"
            else -> "${a.decls.size} declarations ($counts)"
        }
        add("landed ${landing.short} ${Times.short(landing.time)} ($how) · ${a.files.size} files, $declText")
        val byPath = a.decls.orEmpty().groupBy { it.path }
        var listed = 0
        val sources = a.files.keys.filter { it in byPath || codeloupe.lang.Languages.languageOf(it) != null }.sortedWith(PathOrder)
        for (path in sources) {
            val decls = byPath[path].orEmpty().sortedBy { it.startLine }
            if (a.decls != null && decls.isEmpty()) continue
            if (listed++ >= limit) continue
            add(path + status(a.files[path]))
            for (d in decls) {
                if (listed++ >= limit) break
                add("  ${d.mark} ${d.startLine}-${d.endLine}  ${if (d.container.isNotEmpty()) "[${d.container}] " else ""}${shorten(d.sig)}")
            }
        }
        if (listed > limit) add("… +${listed - limit} more lines (raise limit)")
        val quiet = sources.filter { a.decls != null && byPath[it].isNullOrEmpty() }
        if (quiet.isNotEmpty()) add("source files without declaration changes: " + capped(quiet, 10))
        val other = a.files.keys.filter { it !in sources }.sortedWith(PathOrder)
        if (other.isNotEmpty()) add("other files: " + capped(other, 15))
    }

    private fun predicted(p: TouchPrediction.Result, limit: Int): List<String> = buildList {
        if (p.predictions.isEmpty() && p.unresolved.isEmpty()) {
            add("predicted: nothing in the issue text names code")
            return@buildList
        }
        add("predicted from the issue text (= sure · ~ likely · ? guess · + new):")
        val width = p.predictions.take(limit).maxOfOrNull { (it.target.length + it.detail.length).coerceAtMost(TARGET_WIDTH) } ?: 0
        for (pred in p.predictions.take(limit)) {
            val head = listOf(pred.target, shorten(pred.detail)).filter { it.isNotEmpty() }.joinToString("  ")
            add("${pred.mark} ${head.padEnd(width)}  ← ${pred.evidence.joinToString("; ")}")
        }
        if (p.predictions.size > limit) add("… +${p.predictions.size - limit} more predictions (raise limit)")
        if (p.ambiguous.isNotEmpty()) add("ambiguous (declared or used all over): " + names(p.ambiguous))
        if (p.unresolved.isNotEmpty()) add("not in the index: " + names(p.unresolved))
    }

    private fun names(mentions: List<Mention>) = mentions.take(NAMES).joinToString(", ") { "`${it.text}`" } + if (mentions.size > NAMES) ", … +${mentions.size - NAMES}" else ""

    /** Long multi-line parameter lists are cut: the line says which declaration, `symbol` shows it whole. */
    private fun shorten(sig: String) = if (sig.length <= SIG) sig else sig.take(SIG - 1).trimEnd() + "…"

    fun code(target: String, tasks: List<TaskOnCode>, open: List<OpenOnCode>, limit: Int, detailCut: Int): String = buildList {
        val (changed, fileOnly) = tasks.partition { !it.fileOnly }
        if (tasks.isEmpty()) add("no task on the default branch touched $target") else add("tasks that touched $target:")
        for (t in changed.take(limit)) {
            add("${t.task} landed ${t.landing.short} ${Times.short(t.landing.time)}  ${t.marks.joinToString("")}  ${t.row?.let { title(it) } ?: t.landing.subject.take(SUBJECT)}")
        }
        if (changed.size > limit) add("… +${changed.size - limit} more tasks (raise limit)")
        if (fileOnly.isNotEmpty()) {
            add("file touched, this declaration not: " + fileOnly.take(limit).joinToString(" · ") { "${it.task} (${it.landing.short})" } + if (fileOnly.size > limit) " · …" else "")
        }
        if (detailCut > 0) add("$detailCut older commits listed by file only (declaration detail stops at the newest ${MAX_DETAIL})")
        if (open.isNotEmpty()) {
            add("open tasks predicted to touch it:")
            for (o in open.take(limit)) add("${o.task} ${o.prediction.mark} ${o.row?.let { title(it) } ?: ""}  ← ${o.prediction.evidence.first()}")
            if (open.size > limit) add("… +${open.size - limit} more open tasks")
        }
    }.joinToString("\n")

    private fun title(row: TaskRow) = "${row.summary.take(SUBJECT)} (${row.state ?: if (row.resolved) "resolved" else "open"})"

    private fun counts(decls: List<CommitDecl>) =
        listOf(DeclChange.ADDED, DeclChange.BODY, DeclChange.SIGNATURE, DeclChange.REMOVED).map { mark -> mark to decls.count { it.mark == mark } }
            .filter { it.second > 0 }.joinToString(" ") { "${it.first}${it.second}" }

    private fun status(status: Char?) = when (status) {
        'A' -> "  (new)"
        'D' -> "  (deleted)"
        else -> ""
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private fun capped(paths: List<String>, max: Int) = paths.take(max).joinToString(", ") + if (paths.size > max) ", … +${paths.size - max}" else ""

    const val MAX_DETAIL = 30
    private const val SUBJECT = 70
    private const val TARGET_WIDTH = 80
    private const val SIG = 150
    private const val NAMES = 8
    private const val WORKTREE_FILES = 8
}
