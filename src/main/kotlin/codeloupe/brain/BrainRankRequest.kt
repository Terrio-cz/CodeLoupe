package codeloupe.brain

import kotlinx.serialization.Serializable

/** `POST /brain/rank`: the question (role, task, the files it is about, terms) and the lists wanted. */
@Serializable
data class BrainRankRequest(
    /** The workspace directory (the one holding `brain/brain.mjs`); it must be a registered brain. */
    val workspace: String,
    val role: String? = null,
    val task: String? = null,
    val files: List<String> = emptyList(),
    val terms: List<String> = emptyList(),
    /** The checkout to read the files from; default the workspace's `canonicalRepo`. */
    val codeRoot: String? = null,
    val all: Boolean = false,
    val sections: List<BrainSection> = listOf(BrainSection()),
)
