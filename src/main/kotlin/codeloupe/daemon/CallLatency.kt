package codeloupe.daemon

import kotlinx.serialization.Serializable

/** Latency and shape of the most recent calls: [window] calls, percentiles in ms and chars, rates 0..1. */
@Serializable
data class CallLatency(
    val window: Int = 0,
    val p50Ms: Long = 0,
    val p95Ms: Long = 0,
    val p95Chars: Int = 0,
    val emptyRate: Double = 0.0,
    val busyRate: Double = 0.0,
    val byTool: Map<String, ToolLatency> = emptyMap(),
)

@Serializable
data class ToolLatency(val calls: Int, val p50Ms: Long, val p95Ms: Long)
