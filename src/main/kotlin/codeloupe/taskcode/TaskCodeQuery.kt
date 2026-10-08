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

    /** Everything `task_code` knows of one task, before it is rendered; [store] and [repo] let a caller ask the history more. */
    class Facts(val answer: TaskCodeRender.TaskAnswer, val store: TaskCodeStore, val repo: RepoState, val worktree: Path, val note: String?)

    private class Prelude(val dir: Path, val repo: RepoState, val pattern: TaskPattern, val store: TaskCodeStore, val note: String?)

    private suspend fun prelude(root: String): Prelude {
        val location = registry.locate(root)
        val repo = registry.repo(location.commonDir)
        val pattern = TaskPattern.of(registry.mainWorktree(repo.commonDir), trackers.projects())
        val store = registry.taskCodes.current(repo, pattern)
        // Rows, predictions and open tasks come from the mirror: a first load is waited for, a stale one noted.
        val note = if (trackers.configured) trackers.current(initialWaitMs, staleWaitMs) else null
        return Prelude(Path.of(location.worktree), repo, pattern, store, note)
    }

    suspend fun answer(root: String, query: String, limit: Int): String {
        val p = prelude(root)
        val answer = if (p.pattern.isId(query)) TaskCodeRender.task(taskAnswer(root, p.dir, p.repo, p.store, query.trim()), limit) else code(root, p.dir, p.repo, p.store, query.trim(), limit)
        return if (p.note == null) answer else "$answer\n${p.note}"
    }

    /** The code facts of the task [id]: landed, in a worktree, predicted. */
    suspend fun facts(root: String, id: String): Facts {
        val p = prelude(root)
        return Facts(taskAnswer(root, p.dir, p.repo, p.store, id.trim()), p.store, p.repo, p.dir, p.note)
    }

    /** Where a task touches the code: [files] path → mark (`=` sure, `~` likely, `?` guess, `+` new); landed work counts as sure. */
    class Touch(val id: String, val files: Map<String, Char>, val landed: Boolean)

    /** A worktree on a branch other than the main one: the tasks its branch names and the files it changed against the merge-base. */
    class LiveWindow(val branch: String, val path: String, val tasks: List<String>, val changed: Set<String>)

    /** The touch sets of [ids] (landed files, else predicted from the issue text); a task the mirror does not hold has none. */
    suspend fun touches(root: String, ids: List<String>): Map<String, Touch> {
        val p = prelude(root)
        val issues = ids.associateWith { id -> trackers.mirror(id)?.let { (mirror, canonical) -> Triple(mirror, canonical, mirror.store.issue(canonical)) } }
        val landed = withContext(Dispatchers.IO) { ids.associateWith { LandedTask.of(p.store, trackers.mirror(it)?.second ?: it.uppercase()) } }
        val open = ids.filter { landed.getValue(it).commits.isEmpty() && issues[it]?.third != null }
        val predicted = if (open.isEmpty()) emptyMap() else registry.query(root, speculative = false) { view ->
            open.associateWith { id ->
                val (mirror, canonical, issue) = issues.getValue(id)!!
                predict(view, p.dir, p.repo, mirror, canonical, issue!!.updated).predictions
            }
        }
        return ids.associateWith { id ->
            val task = landed.getValue(id)
            if (task.commits.isNotEmpty()) Touch(id, withContext(Dispatchers.IO) { task.files(p.store) }.mapValues { '=' }, true)
            else Touch(id, predicted[id].orEmpty().filter { it.path != null }.groupBy { it.path!! }.mapValues { (_, list) -> list.minBy { Prediction.ORDER.indexOf(it.mark) }.mark }, false)
        }
    }

    /** Every worktree of the repository of [root] that is on a branch and is not the main worktree. */
    suspend fun liveWindows(root: String): List<LiveWindow> {
        val p = prelude(root)
        val main = registry.mainWorktree(p.repo.commonDir).toString().replace('\\', '/')
        return WorktreeBranches.of(p.repo.commonDir).filterValues { !it.equals(main, ignoreCase = true) }.map { (branch, path) ->
            val set = runCatching { registry.changedFiles(path) }.getOrNull()
            LiveWindow(branch, path, p.pattern.idsIn(branch), set?.let { s -> (s.files.map { it.path } + s.otherFiles).toSet() }.orEmpty())
        }
    }

    private suspend fun taskAnswer(root: String, dir: Path, repo: RepoState, store: TaskCodeStore, query: String): TaskCodeRender.TaskAnswer {
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
        return TaskCodeRender.TaskAnswer(id, row, landed, files, decls, worktree, prediction, note)
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
