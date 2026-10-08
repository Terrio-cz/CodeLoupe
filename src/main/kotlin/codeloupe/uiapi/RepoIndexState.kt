package codeloupe.uiapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** State of a repository's base index (docs/ui-spec.md § 9.2). */
@Serializable
enum class RepoIndexState {
    @SerialName("ready") READY,
    @SerialName("building") BUILDING,
    @SerialName("stale") STALE,
    @SerialName("error") ERROR,
    @SerialName("none") NONE,
}
