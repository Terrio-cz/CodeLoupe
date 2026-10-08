package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** A page of tasks; [nextCursor] is opaque and null on the last page. */
@Serializable
data class TaskPage(val items: List<TaskSummary>, val total: Int, val nextCursor: String?, val mirrorSyncedAt: String?)
