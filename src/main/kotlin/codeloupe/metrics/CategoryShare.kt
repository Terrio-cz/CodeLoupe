package codeloupe.metrics

import kotlinx.serialization.Serializable

/** A category's figures over a role's runs and its share of the role's result size, carried size and cost, in percent. */
@Serializable
data class CategoryShare(
    val calls: Int,
    val chars: Long,
    val carried: Long,
    val attr: Long,
    val errors: Int,
    val ms: Long,
    val sharePctChars: Double,
    val sharePctCarried: Double,
    val costPct: Double,
)
