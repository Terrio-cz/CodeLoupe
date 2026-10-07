package codeloupe.jobs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class JobStatus {
    /** Waiting for its slot. */
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,

    /** Ran to its end; `exit` holds the exit code. */
    @SerialName("done") DONE,

    /** The policy hook answered deny or ask; never started. */
    @SerialName("denied") DENIED,
    @SerialName("cancelled") CANCELLED,

    /** Queued or running when its daemon stopped; the log stays. */
    @SerialName("lost") LOST,

    /** Could not be started (no such program, bad directory). */
    @SerialName("error") ERROR,
    ;

    val terminal: Boolean get() = this != QUEUED && this != RUNNING
    val label: String get() = name.lowercase()
}
