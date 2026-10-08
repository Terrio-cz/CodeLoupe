package codeloupe.metrics

import kotlinx.serialization.Serializable

/** Edits of code files in a run and the start of the first few error texts. */
@Serializable
data class CodeEdits(val calls: Int, val errors: Int, val errSamples: List<String?>)
