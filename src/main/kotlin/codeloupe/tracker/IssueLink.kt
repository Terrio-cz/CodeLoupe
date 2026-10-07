package codeloupe.tracker

import kotlinx.serialization.Serializable

/** A link seen from its issue: `<issue> [verb] <other>`, e.g. `depends on TER-3`. */
@Serializable
data class IssueLink(val kind: LinkKind, val verb: String, val other: String)
