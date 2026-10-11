package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** The answer of `brain.mjs search`: a hit is a topic entry, or with `tasks=1` a finding of a past task ([Hit.task]). */
@Serializable
data class BrainSearchView(val workspaceId: String, val terms: List<String>, val hits: List<Hit>, val more: Int) {
    @Serializable
    data class Hit(val where: String, val topic: String? = null, val task: String? = null, val line: Int? = null, val text: String, val hits: Int = 1)
}
