package codeloupe.brain

import kotlinx.serialization.Serializable

@Serializable
data class BrainRankResult(
    val source: String = "daemon",
    val sections: Map<String, BrainRankSection>,
    /** What the code index said about the question's files (null when no file was asked about). */
    val code: CodeContext.CodeSummary? = null,
)
