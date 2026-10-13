package codeloupe.reconcile

import codeloupe.config.ReconcileConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.processes.ProcessEntry
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import java.time.Duration
import java.time.Instant

/**
 * The policy, as a pure function of the inventory and the workspace registry; it reads and changes nothing.
 * Only resources an owner is known for appear: the labelled ones and the ones an adoption rule maps. Unowned
 * resources are not in the plan at all, so no later step can reach them.
 *
 * - protected by a `protect` rule: `protected`, whatever else holds;
 * - released by `ws release` (and created before the release): `auto`, whoever owns it and whatever state the workspace is in;
 * - the workspace is active: kept; the repository is not in the registry at all: kept;
 * - the workspace landed and the resource is labelled by CodeLoupe, has no running sibling and is older than the grace
 *   period: `auto`; adopted, running or abandoned/orphan/gone: `confirm`;
 * - a worktree (not the main checkout, not one of Claude Code's own under `.claude/worktrees/`): released and merged into the
 *   default branch, or landed with its task resolved (or no task) and no git activity for the grace period: `auto`; landed but
 *   its task still open: `confirm`; anything still working in it (a process, a running container): kept; otherwise not planned.
 *   The executor removes nothing that changed since the plan or holds changes git would lose;
 * - an orphan directory under a worktree root: released (what a removal that a lock interrupted leaves): `auto`, else `confirm`;
 * - a build tool (Gradle daemon or worker, Kotlin daemon) working in a workspace directory: released: `auto`; the workspace is
 *   active: kept; landed, abandoned or orphan: `confirm`. Other processes are never planned, only reported.
 */
class ReconcilePlanner(
    private val config: ReconcileConfig,
    /** When a workspace was released (repo, workspace), or null; see [ReleaseStore]. */
    private val releasedAt: (String, String) -> Instant? = { _, _ -> null },
    private val now: () -> Instant = Instant::now,
) {
    fun plan(resources: List<ResourceEntry>, registry: WorkspaceList, processes: List<ProcessEntry> = emptyList()): List<PlanEntry> {
        val knownRepos = registry.repos.mapTo(HashSet()) { it.name.lowercase() }
        val running = resources.filter { it.kind == ResourceKind.CONTAINER && it.state in RUNNING }
            .mapTo(HashSet()) { it.repo.orEmpty().lowercase() to it.workspace.orEmpty().lowercase() }
        val owned = resources.filter { it.ownership != OwnershipClass.UNOWNED }.map { entry(it, it.repo.orEmpty().lowercase() in knownRepos, running) }
        val directories = registry.repos.flatMap { repo -> repo.workspaces.filter { it.role == "directory" && it.state == WorkspaceState.ORPHAN }.map { repo.name to it } }
            .map { (repo, directory) -> directory(repo, directory) }
        val tools = processes.filter { it.kind.buildTool }.map(::process)
        val worktrees = registry.repos.flatMap { repo -> repo.workspaces.mapNotNull { worktree(repo, it, processes, running) } }
        return (owned + directories + tools + worktrees).sortedWith(compareBy({ it.kind.ordinal }, { it.workspace.orEmpty().lowercase() }, { it.name }))
    }

    private fun entry(resource: ResourceEntry, repoKnown: Boolean, running: Set<Pair<String, String>>): PlanEntry {
        val kind = when (resource.kind) {
            ResourceKind.CONTAINER -> TargetKind.CONTAINER
            ResourceKind.NETWORK -> TargetKind.NETWORK
            ResourceKind.VOLUME -> TargetKind.VOLUME
            ResourceKind.IMAGE -> TargetKind.IMAGE
        }
        val name = resource.names.firstOrNull() ?: resource.id
        val key = "${kind.name.lowercase()}:${if (kind == TargetKind.VOLUME) name else resource.id}"
        val protected = config.protect.any { it.covers(resource.kind, resource.names, resource.project) }
        val release = if (protected) null else releasedAt(resource.repo.orEmpty(), resource.workspace.orEmpty())?.takeIf { at -> created(resource)?.let { it <= at } != false }
        val (verdict, reason) = when {
            protected -> Verdict.PROTECTED to "protected by the config"
            release != null -> Verdict.AUTO to "the workspace was released at $release"
            else -> decide(resource, repoKnown, running)
        }
        return PlanEntry(
            key, kind, name, resource.repo, resource.workspace, resource.ownership, resource.workspaceState, verdict, reason, released = release != null,
            running = resource.kind == ResourceKind.CONTAINER && resource.state in RUNNING,
        )
    }

    private fun decide(resource: ResourceEntry, repoKnown: Boolean, running: Set<Pair<String, String>>): Pair<Verdict, String> {
        return when (resource.workspaceState) {
            null -> if (repoKnown) Verdict.CONFIRM to "the workspace is gone from the registry" else Verdict.KEEP to "its repository is not in the registry"
            WorkspaceState.ACTIVE -> Verdict.KEEP to "the workspace is active"
            WorkspaceState.ABANDONED -> Verdict.CONFIRM to "the workspace is abandoned"
            WorkspaceState.ORPHAN -> Verdict.CONFIRM to "the workspace is an orphan"
            WorkspaceState.LANDED -> landed(resource, running)
        }
    }

    private fun landed(resource: ResourceEntry, running: Set<Pair<String, String>>): Pair<Verdict, String> = when {
        resource.ownership != OwnershipClass.OWNED -> Verdict.CONFIRM to "the workspace landed; the resource is adopted by a rule, not labelled"
        resource.repo.orEmpty().lowercase() to resource.workspace.orEmpty().lowercase() in running -> Verdict.CONFIRM to "the workspace landed, but it has running containers"
        young(resource) -> Verdict.KEEP to "younger than ${config.graceMinutes} min"
        else -> Verdict.AUTO to "the workspace landed"
    }

    private fun young(resource: ResourceEntry): Boolean {
        val created = created(resource) ?: return false
        return Duration.between(created, now()) < Duration.ofMinutes(config.graceMinutes.toLong())
    }

    private fun created(resource: ResourceEntry): Instant? = resource.created?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun process(p: ProcessEntry): PlanEntry {
        val protected = config.protect.any { it.covers(null, listOfNotNull(p.commandLine, p.cwd, p.path), null) }
        val release = if (protected) null else releasedAt(p.repo, p.workspace)?.takeIf { at -> Instant.ofEpochMilli(p.startMs) <= at }
        val (verdict, reason) = when {
            protected -> Verdict.PROTECTED to "protected by the config"
            release != null -> Verdict.AUTO to "the workspace was released at $release"
            else -> when (p.workspaceState) {
                WorkspaceState.ACTIVE -> Verdict.KEEP to "the workspace is active"
                WorkspaceState.LANDED -> Verdict.CONFIRM to "the workspace landed; release it to stop its build tools"
                WorkspaceState.ABANDONED -> Verdict.CONFIRM to "the workspace is abandoned"
                WorkspaceState.ORPHAN -> Verdict.CONFIRM to "the workspace is an orphan"
            }
        }
        val name = "${p.kind.name.lowercase().replace('_', '-')} pid ${p.pid} (${p.rssMb} MB)"
        return PlanEntry(p.key, TargetKind.PROCESS, name, p.repo, p.workspace, null, p.workspaceState, verdict, reason, released = release != null, path = p.path)
    }

    private fun directory(repo: String, directory: Workspace): PlanEntry {
        val protected = config.protect.any { it.covers(null, listOf(directory.path, directory.name), null) }
        val release = if (protected) null else releasedAt(repo, directory.name)
        val (verdict, reason) = when {
            protected -> Verdict.PROTECTED to "protected by the config"
            release != null -> Verdict.AUTO to "the workspace was released at $release; git has no worktree for the directory any more"
            else -> Verdict.CONFIRM to "a directory under a worktree root that git has no worktree for"
        }
        return PlanEntry(
            "directory:${directory.path}", TargetKind.DIRECTORY, directory.path, repo, directory.name, null, WorkspaceState.ORPHAN, verdict, reason,
            released = release != null,
        )
    }

    private fun worktree(repo: RepoWorkspaces, w: Workspace, processes: List<ProcessEntry>, running: Set<Pair<String, String>>): PlanEntry? {
        if (w.role != "worktree" || w.state == WorkspaceState.ORPHAN || CLAUDE_WORKTREES in w.path.replace('\\', '/')) return null
        // A repository that names its worktree roots owns only the worktrees under them; one elsewhere (an older tool's checkout) is not ours to remove.
        if (repo.roots.isNotEmpty() && repo.roots.none { under(w.path, it) }) return null
        val released = releasedAt(repo.name, w.name)?.takeIf { w.merge?.merged == true }
        if (w.state != WorkspaceState.LANDED && released == null) return null
        val protected = config.protect.any { it.covers(null, listOf(w.path, w.name), null) }
        val release = if (protected) null else released
        val workspace = repo.name.lowercase() to w.name.lowercase()
        // A released workspace's build tools are stopped by the same run; anything else working in the directory keeps it.
        val busy = processes.count { (it.repo.lowercase() to it.workspace.lowercase()) == workspace && (release == null || !it.kind.buildTool) }
        val (verdict, reason) = when {
            protected -> Verdict.PROTECTED to "protected by the config"
            busy > 0 -> Verdict.KEEP to "$busy process(es) still work in it"
            workspace in running -> Verdict.KEEP to "its containers still run"
            release != null -> Verdict.AUTO to "the workspace was released at $release and its branch is merged into ${w.merge?.defaultRef}"
            w.taskId != null && w.tracker?.resolved != true -> Verdict.CONFIRM to "the workspace landed, but ${w.taskId} is not resolved"
            idleFor(w) < Duration.ofMinutes(config.graceMinutes.toLong()) -> Verdict.KEEP to "git worked in it less than ${config.graceMinutes} min ago"
            else -> Verdict.AUTO to if (w.taskId != null) "the workspace landed and ${w.taskId} is resolved" else "the workspace landed"
        }
        return PlanEntry(
            "worktree:${w.path}", TargetKind.WORKTREE, w.path, repo.name, w.name, null, w.state, verdict, reason, released = release != null,
            path = w.path, gitDir = repo.commonDir, branch = w.branch, head = w.head,
        )
    }

    private fun under(path: String, root: String): Boolean {
        fun norm(p: String) = p.replace('\\', '/').trimEnd('/').lowercase()
        return norm(path).startsWith(norm(root) + "/")
    }

    // A worktree without a readable activity time counts as idle: the scanner only lacks it for one git cannot read.
    private fun idleFor(w: Workspace): Duration =
        w.lastActivity?.let { runCatching { Duration.between(Instant.parse(it), now()) }.getOrNull() } ?: Duration.ofDays(365)

    internal companion object {
        val RUNNING = setOf("running", "restarting", "paused")

        /** Worktrees Claude Code makes for its sessions and agents: it removes them itself. */
        const val CLAUDE_WORKTREES = "/.claude/worktrees/"
    }
}
