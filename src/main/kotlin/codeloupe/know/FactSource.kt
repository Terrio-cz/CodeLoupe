package codeloupe.know

import kotlinx.serialization.Serializable

/** Where a fact came from: the task and the run (session, window) that wrote it. */
@Serializable
data class FactSource(val task: String? = null, val run: String? = null)
