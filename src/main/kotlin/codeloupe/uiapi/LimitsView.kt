package codeloupe.uiapi

import codeloupe.ingest.LimitSnapshot
import kotlinx.serialization.Serializable

/**
 * The `/usage` limits agents' sessions reported: [latest] is the newest snapshot of every meter per account, [snapshots] the series since
 * [since] oldest first (cut to the newest [limit] when [truncated]). A snapshot with a null `account` belongs to a session whose account
 * the data does not say; `account` null in the request means all of them.
 */
@Serializable
data class LimitsView(
    val since: String,
    val account: String?,
    val limit: Int,
    val truncated: Boolean,
    val latest: List<LimitSnapshot>,
    val snapshots: List<LimitSnapshot>,
)
