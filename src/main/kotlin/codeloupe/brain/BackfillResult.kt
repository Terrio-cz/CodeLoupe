package codeloupe.brain

import kotlinx.serialization.Serializable

/** What a backfill pass appended: entries that got anchors, anchors that followed moved code, entries left as they were. */
@Serializable
data class BackfillResult(val anchored: Int = 0, val moved: Int = 0, val skipped: Int = 0, val note: String? = null)
