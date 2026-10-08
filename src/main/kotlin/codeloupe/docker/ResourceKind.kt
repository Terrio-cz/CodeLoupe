package codeloupe.docker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ResourceKind {
    @SerialName("container") CONTAINER,
    @SerialName("image") IMAGE,
    @SerialName("volume") VOLUME,
    @SerialName("network") NETWORK,
}
