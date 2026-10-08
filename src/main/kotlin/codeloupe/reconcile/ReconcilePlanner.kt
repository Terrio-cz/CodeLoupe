package codeloupe.reconcile

import codeloupe.config.ReconcileConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.processes.ProcessEntry
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
 * - an orphan directory under a worktree root: always `confirm`;
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
        return (owned + directories + tools).sortedWith(compareBy({ it.kind.ordinal }, { it.workspace.orEmpty().lowercase() }, { it.name }))
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
        return PlanEntry(
            "directory:${directory.path}", TargetKind.DIRECTORY, directory.path, repo, directory.name, null, WorkspaceState.ORPHAN,
            if (protected) Verdict.PROTECTED else Verdict.CONFIRM, if (protected) "protected by the config" else "a directory under a worktree root that git has no worktree for",
        )
    }

    private companion object {
        val RUNNING = setOf("running", "restarting", "paused")
    }
}
