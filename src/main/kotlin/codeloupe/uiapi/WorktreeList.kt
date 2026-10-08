package codeloupe.uiapi

import kotlinx.serialization.Serializable

@Serializable
data class WorktreeList(val items: List<WorktreeSummary>)
