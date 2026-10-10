package codeloupe.know

import kotlinx.serialization.Serializable

/** A fact a search found, with the compact line an agent reads. */
@Serializable
data class KnowHit(val fact: Fact, val line: String)
