package codeloupe.metrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The starting contexts of a role's runs: median [s], their cost [costSum] and its share of the role's cost, the median
 * tokens per source and the median share of [s] each source makes. [startPct] is the share of the whole report's cost.
 */
@Serializable
data class StartBlock(
    val runs: Int,
    @SerialName("S") val s: Long,
    val costSum: Long,
    val startOfRolePct: Double,
    val tokens: Map<String, Long>,
    val sharePct: Map<String, Double>,
    val startPct: Double? = null,
)
