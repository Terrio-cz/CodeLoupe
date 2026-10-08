package codeloupe.taskcode

import codeloupe.lang.Languages
import codeloupe.query.DeclRow
import codeloupe.query.Resolver
import codeloupe.query.View
import codeloupe.repo.RepoState
import codeloupe.repo.Registry
import codeloupe.tracker.TrackerMirror
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.TaskFilter
import codeloupe.tracker.read.TaskList
import codeloupe.tracker.read.TaskRow
import codeloupe.tracker.read.TaskRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * `task_code`: a task id answers with the code the task touched (landed, from its commits) or will touch (open, from
 * its text, with evidence) and the worktree working on it; a symbol or path answers with the tasks that touched it,
 * each with its landing commit, and the open tasks predicted to. Predictions of open tasks are kept per issue version
 * and base commit, so a repeated `code_tasks` costs nothing new.
 */
class TaskCodeQuery(private val registry: Registry, private val trackers: Trackers, private val initialWaitMs: Long = 10_000, private val staleWaitMs: Long = 3_000) {
    private val predictions = ConcurrentHashMap<String, Pair<String, TouchPrediction.Result>>()
    private val mentions = ConcurrentHashMap<String, Pair<Long, List<Mention>>>()

    suspend fun answer(root: String, query: String, limit: Int): String {
        val location = registry.locate(root)
        val repo = registry.repo(location.commonDir)
        val pattern = TaskPattern.of(registry.mainWorktree(repo.commonDir), trackers.projects())
        val store = registry.taskCodes.current(repo, pattern)
        // Rows, predictions and open tasks come from the mirror: a first load is waited for, a stale one noted.
        val note = if (trackers.configured) trackers.current(initialWaitMs, staleWaitMs) else null
        val dir = Path.of(location.worktree)
        val answer = if (pattern.isId(query)) task(root, dir, repo, store, query.trim(), limit) else code(root, dir, repo, store, query.trim(), limit)
        return if (note == null) answer else "$answer\n$note"
    }

    private suspend fun task(root: String, dir: Path, repo: RepoState, store: TaskCodeStore, query: String, limit: Int): String {
        val mirrored = trackers.mirror(query)
        val id = mirrored?.second ?: query.uppercase()
        val note = mirrored?.first?.refresh(id)?.takeIf { it.startsWith("(") }
        val issue = mirrored?.first?.store?.issue(id)
        val row = mirrored?.let { TaskRows.row(it.first.store, id) }
        val landed = withContext(Dispatchers.IO) { LandedTask.of(store, id) }
        val files = withContext(Dispatchers.IO) { landed.files(store) }
        val decls = withContext(Dispatchers.IO) { landed.decls { registry.taskCodes.decls(repo, store, it) } }
        val worktree = worktree(repo, id)
        val prediction = if (issue != null && (issue.resolved == null || landed.commits.isEmpty())) {
            registry.query(root, speculative = false) { view -> predict(view, dir, repo, mirrored.first, id, issue.updated) }
        } else {
            null
        }
        return TaskCodeRender.task(TaskCodeRender.TaskAnswer(id, row, landed, files, decls, worktree, prediction, note), limit)
    }

    /** The worktree whose branch names [id], with what it changed against the merge-base. */
    private suspend fun worktree(repo: RepoState, id: String): TaskCodeRender.Worktree? {
        val pattern = TaskPattern.of(registry.mainWorktree(repo.commonDir), trackers.projects())
        val (branch, path) = WorktreeBranches.of(repo.commonDir).entries.firstOrNull { id in pattern.idsIn(it.key) } ?: return null
        val set = runCatching { registry.changedFiles(path) }.getOrNull()
            ?: return TaskCodeRender.Worktree(branch, path, emptyList(), emptyList())
        return TaskCodeRender.Worktree(branch, path, set.files.map { it.path }, set.otherFiles)
    }

    private suspend fun code(root: String, worktree: Path, repo: RepoState, store: TaskCodeStore, query: String, limit: Int): String =
        registry.query(root, speculative = false) { view ->
            // A docs or build file is not indexed, but its commits are recorded and it may be on disk.
            val unixed = query.replace('\\', '/').removePrefix("./")
            val path = Resolver.resolvePath(view, query)
                ?: unixed.takeIf { ('/' in it || '.' in it) && (store.commitsTouching(it).isNotEmpty() || Files.isRegularFile(worktree.resolve(it))) }
            val decls = if (path == null) Resolver.resolve(view, query).filter { !it.local } else emptyList()
            if (path == null && decls.isEmpty()) return@query "no declaration or file matches \"$query\" (a task id looks like ${pattern(repo).hint})"
            val target = path ?: decls.map { it.path }.distinct().singleOrNull()
                ?: return@query "\"$query\" names ${decls.size} declarations in ${decls.map { it.path }.distinct().size} files; qualify it (Type.member or pkg.Type)"
            val label = path ?: "${decls.first().let { if (it.container.isEmpty()) it.name else "${it.container}.${it.name}" }} (${target}:${decls.minOf { it.startLine }}-${decls.maxOf { it.endLine }})"
            val touching = store.commitsTouching(target)
            val tasks = tasksOn(repo, store, target, touching, decls.takeIf { path == null })
            val open = openOn(view, worktree, repo, target)
            val detailCut = if (Languages.languageOf(target) == null) 0 else maxOf(0, touching.size - TaskCodeRender.MAX_DETAIL)
            TaskCodeRender.code(label, tasks, open, limit, detailCut)
        }

    /** One entry per task, newest landing first; with [decls] the marks are the declaration's own, else the file's. */
    private fun tasksOn(repo: RepoState, store: TaskCodeStore, path: String, touching: List<Pair<TaskCommit, List<String>>>, decls: List<DeclRow>?): List<TaskCodeRender.TaskOnCode> {
        val rows = rows(touching.flatMap { it.second }.toSet())
        val byTask = LinkedHashMap<String, MutableList<TaskCommit>>()
        for ((commit, tasks) in touching) for (task in tasks) byTask.getOrPut(task) { ArrayList() } += commit
        val detailed = touching.take(TaskCodeRender.MAX_DETAIL).map { it.first.sha }.toSet()
        return byTask.map { (task, commits) ->
            val landing = LandedTask(task, commits.sortedBy { it.time }, emptyList()).landing!!
            val marks = commits.filter { it.sha in detailed }.flatMap { commit ->
                val changed = registry.taskCodes.decls(repo, store, commit, listOf(path)).orEmpty()
                if (decls == null) changed.map { it.mark } else changed.filter { c -> decls.any { d -> d.kind == c.kind && d.container == c.container && d.name == c.name } }.map { it.mark }
            }.distinct()
            TaskCodeRender.TaskOnCode(task, rows[task], landing, marks, fileOnly = decls != null && marks.isEmpty() && commits.any { it.sha in detailed })
        }
    }

    /**
     * Open tasks of the trackers that cover this repository whose text points at [path]. Each task's mentions are
     * matched against this one path (no repository-wide literal search), so a pass over every open task stays cheap.
     */
    private fun openOn(view: View, worktree: Path, repo: RepoState, path: String): List<TaskCodeRender.OpenOnCode> = buildList {
        val prediction = TouchPrediction(view, worktree)
        for (mirror in trackers.mirrors.filter { covers(it, repo) }) {
            for (row in TaskList.matching(mirror.store, TaskFilter.parse("#unresolved"))) {
                val hit = prediction.onPath(mentions(mirror, row.id, row.updated), path) ?: continue
                add(TaskCodeRender.OpenOnCode(row.id, row, hit))
            }
        }
    }.sortedBy { Prediction.ORDER.indexOf(it.prediction.mark) }

    private fun mentions(mirror: TrackerMirror, id: String, updated: Long): List<Mention> {
        mentions[id]?.let { (seen, list) -> if (seen == updated) return list }
        val issue = mirror.store.issue(id) ?: return emptyList()
        return Mentions.of(issue).also { mentions[id] = updated to it }
    }

    private fun predict(view: View, worktree: Path, repo: RepoState, mirror: TrackerMirror, id: String, updated: Long): TouchPrediction.Result {
        val stamp = "$updated@${repo.baseCommit}"
        predictions[id]?.let { (seen, result) -> if (seen == stamp) return result }
        return TouchPrediction(view, worktree).of(mentions(mirror, id, updated)).also { predictions[id] = stamp to it }
    }

    /** A tracker that lists repositories covers only those; one that lists none covers every repository. */
    private fun covers(mirror: TrackerMirror, repo: RepoState): Boolean =
        mirror.instance.repos.isEmpty() || mirror.instance.repos.any { runCatching { registry.locate(it).commonDir }.getOrNull() == repo.commonDir }

    private fun rows(ids: Set<String>): Map<String, TaskRow> =
        trackers.mirrors.flatMap { m -> TaskRows.byIds(m.store, ids.filter { m.canonical(it) != null }).values }.associateBy { it.id.uppercase() }

    private fun pattern(repo: RepoState) = TaskPattern.of(registry.mainWorktree(repo.commonDir), trackers.projects())
}
