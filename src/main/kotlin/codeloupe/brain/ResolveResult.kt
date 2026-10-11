package codeloupe.brain

import kotlinx.serialization.Serializable

@Serializable
data class ResolveResult(val refs: List<ResolvedRef> = emptyList(), val note: String? = null)
