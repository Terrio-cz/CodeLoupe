package codeloupe.jobs

import kotlinx.serialization.Serializable

/** What an agent needs from a log: test counts when the output has them, failure lines, the last lines. */
@Serializable
data class JobSummary(
    val tests: Int? = null,
    val passed: Int? = null,
    val failed: Int? = null,
    val skipped: Int? = null,
    val failures: List<String> = emptyList(),
    val tail: List<String> = emptyList(),
)
