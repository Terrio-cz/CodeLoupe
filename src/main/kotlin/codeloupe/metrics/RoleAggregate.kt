package codeloupe.metrics

import kotlinx.serialization.Serializable

/** The runs of one role in a report, summed up. */
@Serializable
data class RoleAggregate(
    val runs: Int,
    val cost: Pick,
    val output: Pick,
    val cacheRead: Pick,
    val cacheWrite: Pick,
    val peakContext: Pick,
    val turns: Pick,
    val wallSec: Pick,
    val toolSec: Pick,
    val codeReadCalls: Pick,
    val codeReadChars: Pick,
    val codeRereads: Pick,
    val wholeFileReads: Pick,
    val codeEditCalls: Pick,
    val codeEditErrors: Pick,
    val toolErrors: Pick,
    val toolResultCostPct: Double,
    val topCommands: List<TopCommand>,
    val categories: Map<String, CategoryShare>,
    /** The starting contexts of the role's runs; null when none of them has a first turn. */
    val start: StartBlock? = null,
)
