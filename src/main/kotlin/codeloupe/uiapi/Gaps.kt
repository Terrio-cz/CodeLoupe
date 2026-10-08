package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * Where an agent fell back to `rg`/`sed`/`cat` after a CodeLoupe call. The daemon has no gap data of its own until the
 * transcript ingest (CL-62); the weekly report is `codeloupe metrics gaps`. Empty until then.
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
