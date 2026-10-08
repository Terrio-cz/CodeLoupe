package codeloupe.docker

import codeloupe.workspace.WorkspaceState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a resource relates to CodeLoupe: it carries the labels, an adoption rule maps it, or nothing says it is ours. */
@Serializable
enum class OwnershipClass {
    @SerialName("owned") OWNED,
    @SerialName("adopted") ADOPTED,
    @SerialName("unowned") UNOWNED,
}

/** One Docker resource of the inventory. An unowned one is only reported. */
@Serializable
data class ResourceEntry(
    val kind: ResourceKind,
    val id: String,
    val names: List<String>,
    val ownership: OwnershipClass,
    val repo: String? = null,
    val workspace: String? = null,
    val task: String? = null,
    /** `labels`, or `adoption rule <n> (<matched name>)`, counted from 1 in config order. */
    val via: String? = null,
    /** The workspace's state in the registry; null when the registry does not know that workspace. */
    val workspaceState: WorkspaceState? = null,
    val state: String? = null,
    val created: String? = null,
    val project: String? = null,
    /** Containers: the host ports it publishes. */
    val publishedPorts: List<Int> = emptyList(),
    /** Running containers of a workspace, only when asked for (`/resources?stats=1`): one Engine reading each. */
    val memoryBytes: Long? = null,
)
