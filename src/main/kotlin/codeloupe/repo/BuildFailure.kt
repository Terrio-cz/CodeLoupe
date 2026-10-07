package codeloupe.repo

import kotlinx.serialization.Serializable

/** The last base build that failed, shown in `/status` until a build succeeds. */
@Serializable
data class BuildFailure(val commit: String, val at: String, val error: String)
