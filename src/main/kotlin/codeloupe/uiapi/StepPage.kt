package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** A page of a run's steps; [nextCursor] is opaque and null on the last page. */
@Serializable
data class StepPage(val items: List<StepItem>, val total: Int, val nextCursor: String?)
