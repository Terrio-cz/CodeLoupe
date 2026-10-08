package codeloupe.reconcile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the reconciler can remove, in the order it removes them: build daemons first, since they hold the worktree directory,
 * then a network cannot go while a container uses it, an image while a container is built on it.
 */
@Serializable
enum class TargetKind {
    @SerialName("process") PROCESS,
    @SerialName("container") CONTAINER,
    @SerialName("network") NETWORK,
    @SerialName("volume") VOLUME,
    @SerialName("image") IMAGE,
    @SerialName("directory") DIRECTORY,
}
