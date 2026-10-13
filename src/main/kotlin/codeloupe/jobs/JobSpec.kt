package codeloupe.jobs

import kotlinx.serialization.Serializable

/**
 * What a job needs to start again after a daemon restart and that its [JobRecord] does not hold: the argv and the `--env` values
 * as given, and the completion steps unscrubbed. Stored only sealed ([JobSpecKey]) and only while the job is queued or running.
 */
@Serializable
data class JobSpec(
    val command: List<String>,
    val env: Map<String, String> = emptyMap(),
    val then: List<String> = emptyList(),
    val onFailure: List<String> = emptyList(),
)
