package codeloupe.know

import kotlinx.serialization.Serializable

/**
 * A fact as a writer sends it (`POST /know`). [scope] reads `area:docker`; [anchors] without a root use [root], or the daemon's
 * default root. With [verify] the anchors are fingerprinted now and the fact counts as verified at the checkout's commit.
 * [superseded] stores an already replaced fact (an import of a note that says so).
 */
@Serializable
data class FactDraft(
    val scope: String,
    val text: String,
    val kind: String = FactKind.OTHER.word,
    val task: String? = null,
    val run: String? = null,
    val root: String? = null,
    val anchors: List<AnchorRef> = emptyList(),
    val supersedes: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val verifiedAtCommit: String? = null,
    val createdAt: String? = null,
    val superseded: Boolean = false,
    val verify: Boolean = true,
)
