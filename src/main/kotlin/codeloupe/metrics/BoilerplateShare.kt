package codeloupe.metrics

import kotlinx.serialization.Serializable

/**
 * New code files and how much of their text is skeleton (package, imports, type headers, annotations, closing braces,
 * blank lines): sizes in characters, [cost] the relative price of writing and carrying them, [boilerplateCost] its skeleton part.
 */
@Serializable
data class BoilerplateShare(
    val files: Int = 0,
    val chars: Long = 0,
    val boilerplateChars: Long = 0,
    val cost: Long = 0,
    val boilerplateCost: Long = 0,
) {
    val sharePct: Double get() = if (chars == 0L) 0.0 else Math.round(1000.0 * boilerplateChars / chars) / 10.0

    operator fun plus(other: BoilerplateShare) = BoilerplateShare(
        files + other.files, chars + other.chars, boilerplateChars + other.boilerplateChars, cost + other.cost, boilerplateCost + other.boilerplateCost,
    )
}
