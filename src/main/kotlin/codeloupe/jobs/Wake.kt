package codeloupe.jobs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * When the end of a job chain should wake the agent that started it. CodeLoupe only marks its last `job.finished`
 * event with `wake`; the launcher (or a waiting `codeloupe job wait`) decides what to do with it.
 */
@Serializable
enum class Wake {
    @SerialName("always") ALWAYS,

    /** Only when a job in the chain failed: a passing chain finishes without an agent turn. */
    @SerialName("failure") FAILURE,
    @SerialName("never") NEVER,
    ;

    companion object {
        fun parse(text: String): Wake = entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("wake must be always, failure or never")
    }
}
