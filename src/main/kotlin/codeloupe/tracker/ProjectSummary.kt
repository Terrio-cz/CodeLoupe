package codeloupe.tracker

import kotlinx.serialization.Serializable

/** A mirrored project in `/status`; [lastSync] is ISO time or null before the first sync. */
@Serializable
data class ProjectSummary(val project: String, val issues: Int, val lastSync: String?, val error: String? = null)
