package codeloupe.metrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a run carried into its first turn: [s] tokens (input + cache read + cache write of that turn), [cost] in relative
 * price units (what turn 1 paid plus one cache read per later turn) and the characters of everything the transcript holds
 * before that turn, by source ([chars]). [mcpSchemas] is the size of the MCP tool schemas where a report measured them.
 */
@Serializable
data class StartCtx(
    @SerialName("S") val s: Long,
    val cost: Long,
    val chars: Map<String, Long>,
    val mcpSchemas: Long? = null,
)
