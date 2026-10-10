package codeloupe.know

import kotlinx.serialization.Serializable

/** The answer of a search. */
@Serializable
data class KnowHits(val hits: List<KnowHit>)
