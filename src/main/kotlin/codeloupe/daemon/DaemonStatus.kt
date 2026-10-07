package codeloupe.daemon

import codeloupe.repo.RepoRecord
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
    val repos: List<RepoRecord>,
)
