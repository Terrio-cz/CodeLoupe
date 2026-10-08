package codeloupe.metrics

import kotlinx.serialization.Serializable

/** One line of the weekly gap report: how often [kind] happened to queries of this [shape], and a few things asked. */
@Serializable
data class GapRow(val week: String, val tool: String, val shape: String, val kind: String, val count: Int, val examples: List<String>)
