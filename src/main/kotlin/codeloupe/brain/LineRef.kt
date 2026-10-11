package codeloupe.brain

import kotlinx.serialization.Serializable

/** A `file:line` a finding cites; [text] is the finding, used to check that the declaration found there is what the finding is about. */
@Serializable
data class LineRef(val file: String, val line: Int, val text: String? = null)
