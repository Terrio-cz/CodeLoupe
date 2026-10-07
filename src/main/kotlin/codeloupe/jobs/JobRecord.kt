package codeloupe.jobs

import kotlinx.serialization.Serializable

/**
 * A job as stored in `<home>/jobs.db` and shown to clients. Holds no secrets: the command is scrubbed, the environment
 * is kept by name only, and the follow-up actions only as their (scrubbed) specs.
 */
@Serializable
data class JobRecord(
    val id: String,
    /** First job of the chain this one belongs to; its own id when it started the chain. */
    val rootId: String,
    val parentId: String? = null,
    /** The follow-up job a completion action started, once this one finished. */
    val nextId: String? = null,
    val tag: String? = null,
    val command: String,
    val cwd: String,
    val envNames: List<String> = emptyList(),
    val slot: String? = null,
    val status: JobStatus,
    val exit: Int? = null,
    val reason: String? = null,
    val createdAt: String,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationMs: Long? = null,
    val log: String,
    val pid: Long? = null,
    /** Start time of [pid], so a restarted daemon never kills an unrelated process that reused the pid. */
    val pidStart: String? = null,
    val summary: JobSummary? = null,
    /** When the end of its chain wakes the agent; the `job.finished` event carries the outcome as `wake`. */
    val wakeOn: Wake,
    val then: List<String> = emptyList(),
    val onFailure: List<String> = emptyList(),
    /** Started by an `onFailure` action: the chain failed even when this job passes. */
    val failureBranch: Boolean = false,
) {
    val ok: Boolean get() = status == JobStatus.DONE && exit == 0
}
