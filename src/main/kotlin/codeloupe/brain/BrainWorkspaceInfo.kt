package codeloupe.brain

import kotlinx.serialization.Serializable

/** What `brain.mjs export` says about the workspace: where its code lives and what each role reads. */
@Serializable
data class BrainWorkspaceInfo(
    val name: String = "",
    val dir: String = "",
    val canonicalRepo: String? = null,
    val taskIdPattern: String? = null,
    val roles: List<String> = emptyList(),
    val briefs: Map<String, BrainRoleBrief> = emptyMap(),
)
