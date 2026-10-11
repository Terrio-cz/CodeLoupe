package codeloupe.brain

import kotlinx.serialization.Serializable

@Serializable
data class ResolvedRef(val file: String, val line: Int, val anchors: List<String>)
