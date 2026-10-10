package codeloupe.taskcode

import codeloupe.doc.Doc
import codeloupe.lang.Languages
import codeloupe.repo.Registry
import codeloupe.tracker.Criterion
import codeloupe.tracker.LinkKind
import codeloupe.tracker.Times
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.IssueContext
import codeloupe.tracker.read.IssueRender
import codeloupe.tracker.read.Parts
import codeloupe.tracker.read.TaskRows
import kotlinx.coroutines.CancellationException

/**
 * What a planner needs to start on a task, as one [Doc] of sections instead of the issue, related and search round trips:
 * the brief issue with its criteria, the description sections and the comment thread (capped), the linked tasks, the open
 * criteria of the related open tasks, what the task landed or is predicted to touch, the declarations of those files as
 * outline lines, who references them, the earlier tasks that changed the same files, each with its landing commit, and the
 * lines of AGENTS.md and the documents that bear on that code. Every part is compact and capped; reading it again goes through
 * the shared document reader, so an unchanged task costs one line and a changed one only its changed sections.
 */
class TaskContext(private val registry: Registry, private val trackers: Trackers, private val code: TaskCodeQuery) {
    private val callers = TouchedCallers(registry)

    suspend fun build(root: String, id: String): Doc {
        val (mirror, canonical) = trackers.mirror(id)
            ?: throw IllegalArgumentException("no tracker mirrors the project of '$id'; mirrored: ${trackers.projects().joinToString(", ")}")
        val note = mirror.refresh(canonical)
        val issue = mirror.store.issue(canonical) ?: throw IllegalArgumentException(note ?: "no issue $canonical")
        val context = IssueContext(mirror.store, issue)
        var failure: String? = null
        val facts = try {
            code.facts(root, canonical)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message.orEmpty().lineSequence().first()
            null
        }
        val paths = facts?.let { paths(it) }.orEmpty()
        val pieces = listOf(
            "issue" to IssueRender.render(context, Parts.of(null, emptyList())),
            "description" to IssueDescription.render(issue.description),
            "comments" to CommentDigest.render(context.comments),
            "linked" to linked(context),
            "open-criteria" to openCriteria(context, mirror.store),
            "touch" to touch(facts, failure),
            "declarations" to declarations(root, paths),
            "callers" to optional { callers.render(root, paths) },
            "prior" to prior(facts, canonical, paths, mirror.store),
            "norms" to optional { norms(facts, paths) },
        )
        return Doc.of("context:$canonical", pieces.filter { it.second.isNotBlank() }.map { (handle, text) -> Triple(handle, handle, "## $handle\n$text") })
    }

    /** The extra parts of the pack are a help, not the answer: one that fails (an unreadable file, a hostile document) is left out. */
    private suspend fun optional(part: suspend () -> String): String = try {
        part()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ""
    }

    private fun linked(c: IssueContext): String {
        val links = c.issue.links.filter { it.kind != LinkKind.PARENT }
        val lines = links.take(MAX_LINKED).map { "${it.verb} " + (c.linkedRow(it.other)?.line(withParent = false) ?: "${it.other} (not mirrored)") }
        return (lines + listOfNotNull(links.size.takeIf { it > MAX_LINKED }?.let { "… +${it - MAX_LINKED} more links" })).joinToString("\n")
    }

    /** The unchecked criteria of the related tasks that are still open: what the neighbours still owe. */
    private fun openCriteria(c: IssueContext, store: codeloupe.tracker.mirror.MirrorStore): String {
        val related = c.issue.links.filter { it.kind != LinkKind.PARENT }.mapNotNull { link -> c.linkedRow(link.other)?.takeUnless { it.resolved } }.distinctBy { it.id }
        val lines = ArrayList<String>()
        for (row in related.take(MAX_RELATED)) {
            val open = store.issue(row.id)?.let { Criterion.parse(it.description) }.orEmpty().filter { !it.done }
            open.take(PER_TASK).forEach { lines += "${row.id} [ ] ${it.text.take(CRITERION)}" }
            if (open.size > PER_TASK) lines += "${row.id} … +${open.size - PER_TASK} more open criteria"
        }
        return lines.take(MAX_CRITERIA).joinToString("\n")
    }

    private fun touch(f: TaskCodeQuery.Facts?, failure: String?): String {
        if (f == null) return "(code facts unavailable: ${failure ?: "no repository"})"
        // The first line of the task_code answer is the task row, which the issue section already has.
        return TaskCodeRender.task(f.answer, TOUCH_LINES).lines().drop(1).joinToString("\n")
    }

    /**
     * The source files the task landed or is predicted (surely or likely) to touch; only when the text names none, the first
     * few the worktree changed, so a task far along does not drown the pack in its own diff.
     */
    private fun paths(f: TaskCodeQuery.Facts): List<String> {
        val a = f.answer
        val landed = a.files.filterValues { it != 'D' }.keys
        val predicted = a.prediction?.predictions.orEmpty().filter { it.mark == Prediction.SURE || it.mark == Prediction.LIKELY }.mapNotNull { it.path }
        fun sources(paths: Collection<String>) = paths.distinct().filter { Languages.languageOf(it) != null }
        return sources(landed + predicted).ifEmpty { sources(a.worktree?.files.orEmpty()).take(WORKTREE_FILES) }.take(MAX_FILES)
    }

    private suspend fun declarations(root: String, paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val lines = registry.query(root, speculative = false) { view ->
            paths.flatMap { path ->
                // A private member says nothing a planner can build on.
                val decls = view.decls("f.path = :path AND d.local = 0", mapOf("path" to path), "ORDER BY start_line").filterNot { it.sig.startsWith("private ") }
                if (decls.isEmpty()) emptyList()
                else listOf(path) + decls.take(MAX_DECLS).map { "  ${it.startLine}-${it.endLine}  ${if (it.container.isNotEmpty()) "[${it.container}] " else ""}${shorten(it.sig)}" } +
                    listOfNotNull(decls.size.takeIf { it > MAX_DECLS }?.let { "  … +${it - MAX_DECLS} more (outline $path)" })
            }
        }
        return lines.joinToString("\n")
    }

    /** The earlier tasks that changed these files, newest landing first, with the commit that landed each. */
    private fun prior(f: TaskCodeQuery.Facts?, self: String, paths: List<String>, store: codeloupe.tracker.mirror.MirrorStore): String {
        if (f == null || paths.isEmpty()) return ""
        val touched = LinkedHashMap<String, MutableList<String>>()
        for (path in paths) {
            for ((_, tasks) in f.store.commitsTouching(path).take(COMMITS_PER_FILE)) {
                tasks.filter { !it.equals(self, ignoreCase = true) }.forEach { touched.getOrPut(it) { ArrayList() }.let { files -> if (path !in files) files += path } }
            }
        }
        val landed = touched.keys.map { LandedTask.of(f.store, it) }.filter { it.landing != null }.sortedByDescending { it.landing!!.time }.take(MAX_PRIOR)
        val rows = TaskRows.byIds(store, landed.map { it.task })
        return landed.joinToString("\n") { task ->
            val landing = task.landing!!
            val title = rows[task.task.uppercase()]?.summary ?: landing.subject
            "${task.task} landed ${landing.short} ${Times.short(landing.time)}  ${title.take(SUMMARY)}  ‹${touched.getValue(task.task).take(3).joinToString(", ") { it.substringAfterLast('/') }}›"
        }
    }

    /** The repository's own rules and documents that bear on the touched code, so the planner reads those lines and not the whole files. */
    private fun norms(f: TaskCodeQuery.Facts?, paths: List<String>): String {
        if (f == null) return ""
        // New files count for the areas they join: their module and folders tell which rules apply.
        val fresh = f.answer.prediction?.predictions.orEmpty().filter { it.mark == Prediction.NEW }.mapNotNull { it.path }
        val terms = NormTerms.of((paths + fresh).distinct())
        val docs = DocMentions.render(f.worktree, terms)
        val docsText = if (docs.isEmpty()) "" else "documents that name the touched files:" + NL + docs
        return listOf(AgentsNorms.render(f.worktree, terms), docsText).filter { it.isNotEmpty() }.joinToString(NL)
    }

    private fun shorten(sig: String) = sig.lineSequence().first().let { if (it.length <= SIG) it else it.take(SIG - 1).trimEnd() + "…" }

    private companion object {
        const val NL = "\n"
        const val MAX_LINKED = 12
        const val MAX_RELATED = 6
        const val PER_TASK = 3
        const val MAX_CRITERIA = 6
        const val CRITERION = 110
        const val TOUCH_LINES = 20
        const val MAX_FILES = 6
        const val WORKTREE_FILES = 3
        const val MAX_DECLS = 8
        const val COMMITS_PER_FILE = 40
        const val MAX_PRIOR = 6
        const val SUMMARY = 70
        const val SIG = 100
    }
}
