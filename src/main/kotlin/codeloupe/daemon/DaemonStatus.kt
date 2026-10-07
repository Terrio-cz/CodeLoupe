package codeloupe.daemon

import codeloupe.jobs.JobsSnapshot
import codeloupe.platform.PartTime
import codeloupe.repo.RepoSummary
import codeloupe.tracker.TrackerSummary
import kotlinx.serialization.Serializable

/** `GET /status`: what the daemon is, what it costs and what it is doing. */
@Serializable
data class DaemonStatus(
    val name: String,
    val version: String,
    val pid: Long,
    val port: Int,
    val home: String,
    val uptimeSec: Long,
    val rssMb: Long?,
    val heapMb: Long,
    val cpuSec: Long,
    val calls: CallStats,
    val queue: QueueSnapshot,
    val repos: List<RepoSummary>,
    val jobs: JobsSnapshot,
    val trackers: List<TrackerSummary> = emptyList(),
    /** git processes started since the daemon started. */
    val gitSpawns: Long = 0,
    /** Time spent per [codeloupe.platform.TimedPart], lower-case names. */
    val timings: Map<String, PartTime> = emptyMap(),
)
