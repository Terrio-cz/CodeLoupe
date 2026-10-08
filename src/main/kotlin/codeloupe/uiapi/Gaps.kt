package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * Where an agent fell back to `rg`/`sed`/`cat` after a CodeLoupe call, or got no usable answer. Read from the transcript ingest
 * (docs/ui-spec.md § 9.12); the weekly report on the command line is `codeloupe metrics gaps`.
 */
@Serializable
data class Gaps(val summary: List<Summary>, val items: List<Item>) {
    @Serializable
    data class Summary(val tool: String, val shape: String, val fallback: String, val count: Int, val lastAt: String)

    @Serializable
    data class Item(
        val id: String,
        val at: String,
        val tool: String,
        val shape: String,
        val fallback: String,
        val reason: String,
        val session: String,
        val turn: Int?,
        val target: String,
    )
}
