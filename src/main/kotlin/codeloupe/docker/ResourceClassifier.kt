package codeloupe.docker

import codeloupe.config.AdoptionRule
import codeloupe.workspace.WorkspaceState

/**
 * Sorts Docker resources into owned (CodeLoupe labels), adopted (an [AdoptionRule] maps the name) and unowned.
 * Pure: it reads nothing and changes nothing. [states] gives a workspace's registry state by repo and workspace name.
 */
class ResourceClassifier(private val rules: List<AdoptionRule>, private val states: (repo: String, workspace: String) -> WorkspaceState?) {
    fun classify(resources: List<DockerObject>): List<ResourceEntry> = resources.map(::classify)
        .sortedWith(compareBy({ it.ownership.ordinal }, { it.workspace.orEmpty().lowercase() }, { it.kind.ordinal }, { it.names.firstOrNull().orEmpty() }))

    private fun classify(resource: DockerObject): ResourceEntry {
        val base = ResourceEntry(
            resource.kind, resource.id, resource.names, OwnershipClass.UNOWNED, state = resource.state, created = resource.created, project = resource.project,
        )
        Ownership.of(resource.labels)?.let { return owned(base, OwnershipClass.OWNED, it, "labels") }
        rules.forEachIndexed { index, rule -> rule.apply(resource)?.let { return owned(base, OwnershipClass.ADOPTED, it.ownership, "adoption rule ${index + 1} (${it.matched})") } }
        return base
    }

    private fun owned(base: ResourceEntry, ownership: OwnershipClass, owner: Ownership, via: String) = base.copy(
        ownership = ownership, repo = owner.repo, workspace = owner.workspace, task = owner.task.ifEmpty { null }, via = via,
        workspaceState = states(owner.repo, owner.workspace),
    )
}
