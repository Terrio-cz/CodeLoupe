package codeloupe.hooks

import kotlinx.serialization.Serializable

/**
 * What a session carries, from its transcript: the context at its last assistant turn, the tool results that weigh most of what is
 * carried, and which of the configured sizes it has reached. Names of tools and turn numbers only, never a result's text.
 */
@Serializable
data class SessionWeight(
    val contextTokens: Long,
    val turns: Int,
    /** The results with the highest `carried` (characters × the turns that read them, the next one included), heaviest first. */
    val heavy: List<Heavy>,
    /** How many of the configured sizes the context has reached: 0 = none. */
    val level: Int,
    /** False while a long transcript is still being read for the first time: [heavy] is then empty. */
    val complete: Boolean,
) {
    @Serializable
    data class Heavy(val turn: Int, val tool: String, val tokens: Long)

    /** Share of the context that [heavy] takes up, in percent. */
    val heavyShare: Int get() = if (contextTokens <= 0) 0 else minOf(100, (heavy.sumOf { it.tokens } * 100 / contextTokens).toInt())

    /** The one line shown to the user when a size is reached. */
    fun advisory(): String {
        val size = "~${(contextTokens + 500) / 1_000}k tokens"
        val results = if (heavy.size == 1) "one tool result" else "${heavy.size} tool results"
        val names = heavy.sortedBy { it.turn }.joinToString(", ") { "${it.tool} in turn ${it.turn}" }
        // When single results hold little, the weight is the conversation itself and naming them would mislead.
        val where = when {
            heavy.isEmpty() -> ""
            heavyShare >= NAMED_SHARE -> ", $heavyShare % of it in $results ($names)"
            else -> ", only $heavyShare % of it in the $results that weigh most ($names)"
        }
        return "CodeLoupe: this session carries $size$where. Each turn reads it again: /compact, or start a new session when the task changes."
    }
}

/** From this share on, naming the heaviest results tells the user where the weight is. */
private const val NAMED_SHARE = 15
