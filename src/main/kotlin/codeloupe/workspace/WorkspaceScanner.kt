package codeloupe.workspace

import codeloupe.git.CommitInfo
import codeloupe.git.GitLayout
import codeloupe.git.GitObjects
import codeloupe.git.WorktreeGit
import codeloupe.git.WorktreeRegistration
import codeloupe.platform.IsoTime
import codeloupe.repo.DefaultRef
import codeloupe.taskcode.TaskPattern
import codeloupe.tracker.read.TaskRow
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The workspaces of one repository: every worktree git registered (read from the `.git` files and JGit, no git
 * process unless the layout is an unusual one) and every directory under the worktree roots it did not.
 * [tasks] gives a task's row from the tracker mirror, null when there is none; [landed] says whether the default
 * branch holds commits of a task.
 */
class WorkspaceScanner(
    private val abandonedDays: Int,
    private val tasks: (String) -> TaskRow?,
    private val landed: (String) -> Boolean = { false },
    private val now: () -> Instant = Instant::now,
    /** Whether the worktrees of a repository are read on a few threads; the result is the same either way. */
    private val parallel: Boolean = true,
) {
    fun scan(commonDir: String, mainWorktree: Path, pattern: TaskPattern, roots: List<String>, withSize: Boolean): RepoWorkspaces {
        val defaultRef = DefaultRef.of(commonDir)
        val defaultTip = GitObjects.resolve(commonDir, defaultRef)
        val registrations = GitLayout.registrations(commonDir) ?: viaGit(commonDir)
        val known = registrations.mapTo(HashSet()) { OrphanDirs.key(Path.of(it.path)) }
        val registered = ScanPool.map(registrations, parallel) { i, r -> registered(commonDir, r, i == 0, defaultRef, defaultTip, pattern) }
        val orphans = roots.flatMap { OrphanDirs.under(it, commonDir, known) }
        val all = (registered + orphans).map { if (withSize) it.copy(sizeBytes = DiskSize.of(Path.of(it.path))) else it }
            .sortedWith(compareBy({ it.role != "main" }, { it.state.ordinal }, { it.name.lowercase() }))
        return RepoWorkspaces(
            repo = mainWorktree.toString().replace('\\', '/'), name = mainWorktree.name, commonDir = commonDir, defaultRef = defaultRef, roots = roots,
            counts = WorkspaceState.entries.associate { s -> s.name.lowercase() to all.count { it.state == s } }, workspaces = all,
        )
    }

    private fun registered(commonDir: String, r: WorktreeRegistration, main: Boolean, defaultRef: String, defaultTip: String?, pattern: TaskPattern): Workspace {
        val dir = Path.of(r.path)
        val name = dir.name
        val headText = runCatching { r.adminDir.resolve("HEAD").readText().trim() }.getOrNull()
        val branch = headText?.takeIf { it.startsWith(BRANCH_PREFIX) }?.removePrefix(BRANCH_PREFIX)
        val head = if (branch != null) GitObjects.resolve(commonDir, "refs/heads/$branch") else headText?.takeIf { SHA.matches(it) }
        val info = head?.let { GitObjects.commitInfo(commonDir, it) }
        val activity = lastActivity(r.adminDir, info)
        val base = Workspace(
            path = r.path, name = name, role = if (main) "main" else "worktree", state = WorkspaceState.ACTIVE, branch = branch, head = head,
            lastActivity = activity?.let(IsoTime::of),
        )
        if (!Files.isDirectory(dir)) return base.copy(state = WorkspaceState.ORPHAN, note = "registered, but the directory is gone (git worktree prune)")
        if (main) return base
        val taskId = pattern.idsIn(branch.orEmpty()).firstOrNull() ?: pattern.idsIn(name).firstOrNull()
        val tracker = taskId?.let { id -> runCatching { tasks(id) }.getOrNull() }?.let { TrackerState(it.state, it.resolved, it.summary) }
        val merge = if (head != null && defaultTip != null) {
            val ahead = GitObjects.ahead(commonDir, head, defaultTip)
            MergeState(defaultRef, ahead, merged = ahead == 0, subject = info?.subject)
        } else {
            null
        }
        // A branch cut from the default branch and not yet committed on is "merged" too: it counts as landed only
        // when something says its work is done (the task is resolved or has commits on the default branch).
        val isLanded = merge != null && merge.merged && when {
            taskId == null -> head != defaultTip
            else -> tracker?.resolved == true || landed(taskId) || taskId in pattern.idsIn(merge.subject.orEmpty())
        }
        val idle = activity != null && Duration.between(activity, now()) > Duration.ofDays(abandonedDays.toLong())
        val state = when {
            isLanded -> WorkspaceState.LANDED
            idle -> WorkspaceState.ABANDONED
            else -> WorkspaceState.ACTIVE
        }
        val note = if (tracker?.resolved == true && merge?.merged == false) "task is resolved, but the branch is not on $defaultRef" else null
        return base.copy(state = state, note = note, taskId = taskId, merge = merge, tracker = tracker)
    }

    // The newer of the HEAD commit and the last git operation in the worktree (checkout, add and commit touch these files).
    private fun lastActivity(adminDir: Path, commit: CommitInfo?): Instant? =
        listOfNotNull(commit?.let { Instant.ofEpochSecond(it.timeSec) }, modified(adminDir.resolve("index")), modified(adminDir.resolve("HEAD"))).maxOrNull()

    private fun modified(file: Path): Instant? = runCatching { Files.getLastModifiedTime(file).toInstant() }.getOrNull()

    private fun viaGit(commonDir: String): List<WorktreeRegistration> =
        WorktreeGit.list(commonDir).map { WorktreeRegistration(it.replace('\\', '/'), GitLayout.gitDir(Path.of(it)) ?: Path.of(it)) }

    private companion object {
        const val BRANCH_PREFIX = "ref: refs/heads/"
        val SHA = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    }
}
