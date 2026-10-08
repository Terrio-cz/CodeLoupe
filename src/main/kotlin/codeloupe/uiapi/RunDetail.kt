package codeloupe.uiapi

import kotlinx.serialization.Serializable

/** One run with its tokens by price class and what its tool calls cost by category. */
@Serializable
data class RunDetail(val run: RunItem, val usage: Usage, val categories: List<Category>) {
    @Serializable
    data class Usage(val input: Long, val cacheWrite5m: Long, val cacheWrite1h: Long, val cacheRead: Long, val output: Long)

    @Serializable
    data class Category(val category: String, val calls: Int, val chars: Long, val carried: Long, val weighted: Long, val errors: Int)
}
