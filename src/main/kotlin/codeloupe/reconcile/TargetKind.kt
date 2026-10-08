package codeloupe.reconcile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the reconciler can remove, in the order it removes them: a network cannot go while a container uses it, an image while a container is built on it. */
@Serializable
enum class TargetKind {
    @SerialName("container") CONTAINER,
    @SerialName("network") NETWORK,
    @SerialName("volume") VOLUME,
    @SerialName("image") IMAGE,
    @SerialName("directory") DIRECTORY,
}
