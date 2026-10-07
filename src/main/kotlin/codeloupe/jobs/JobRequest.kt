package codeloupe.jobs

import kotlinx.serialization.Serializable

/** `POST /jobs`. [env] is added to the daemon's environment for this job and never stored or emitted. */
@Serializable
data class JobRequest(
    val command: List<String>,
    val cwd: String,
    val env: Map<String, String> = emptyMap(),
    val slot: String? = null,
    val then: List<String> = emptyList(),
    val onFailure: List<String> = emptyList(),
    /** always | failure | never; default failure when [then] declares follow-ups, else always. */
    val wake: String? = null,
    val tag: String? = null,
)
