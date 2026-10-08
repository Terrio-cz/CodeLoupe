package codeloupe.uiapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** State of a worktree's overlay layer; the names are what `Overlays.layer` answers. */
@Serializable
enum class LayerState {
    @SerialName("fresh") FRESH,
    @SerialName("stale") STALE,
    @SerialName("building") BUILDING,
    @SerialName("error") ERROR,
    @SerialName("none") NONE;

    companion object {
        fun of(name: String): LayerState = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}
