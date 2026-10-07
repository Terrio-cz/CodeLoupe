package codeloupe.jobs

/**
 * What happens after a job, declared when it starts: a closed set of typed actions, never a shell string.
 * New kinds (YouTrack updates through the mirror, CL-28; workspace release, CL-69) are new subtypes here, a new
 * prefix in [ActionParser] and a branch in [JobRunner]'s action step.
 */
sealed interface Action {
    /** Runs only when this holds for the job that just finished; null = always. */
    val condition: Condition?

    /** As written, for display (scrubbed before it leaves the daemon). */
    val spec: String

    /** Another job, in the same directory and environment; the rest of the chain continues after it. */
    data class RunJob(override val condition: Condition?, override val spec: String, val command: List<String>, val slot: String?) : Action

    /** A `job.notify` event that wakes the agent, with [message] and the job's summary. */
    data class Notify(override val condition: Condition?, override val spec: String, val message: String) : Action

    /** The job's `job.finished` event POSTed to [url], signed and retried like a subscription. */
    data class Webhook(override val condition: Condition?, override val spec: String, val url: String) : Action
}
