package codeloupe.brain

import kotlinx.serialization.Serializable

/** What the code index says about one file of the question: where it is, its module, how many declarations, who calls it, which tasks changed it. */
@Serializable
data class CodeFileFacts(
    val path: String,
    val module: String = "",
    val declarations: Int = 0,
    val callerFiles: Int = 0,
    val tasks: List<String> = emptyList(),
)
