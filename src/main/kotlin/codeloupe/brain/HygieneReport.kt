package codeloupe.brain

import kotlinx.serialization.Serializable

/**
 * What the nightly retro looks at: entries that say nearly the same thing (merge proposals) and anchors whose code is gone or moved. Proposals
 * only: nothing here rewrites a brain file, the retro records its decision with `fact ... --supersedes <id>`.
 */
@Serializable
data class HygieneReport(
    val duplicates: List<Duplicate> = emptyList(),
    val dangling: List<Dangling> = emptyList(),
    val moved: List<Moved> = emptyList(),
    val checkedAnchors: Int = 0,
    val note: String? = null,
) {
    @Serializable
    data class Duplicate(val a: String, val b: String, val similarity: Double, val aText: String, val bText: String, val aWhere: String, val bWhere: String)

    /** [anchor] of entry [id] names code that no longer exists: a candidate to be superseded. */
    @Serializable
    data class Dangling(val id: String, val anchor: String, val text: String)

    @Serializable
    data class Moved(val id: String, val from: String, val to: String)
}
