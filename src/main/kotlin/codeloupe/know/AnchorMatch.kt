package codeloupe.know

import codeloupe.query.Like

/**
 * Whether a fact's anchor is the code a search names (`know search --anchor`). A path matches whole or as its tail (`Foo.kt` finds
 * `src/Foo.kt`), a symbol whole, as a member (`Foo` finds `Foo.parse`) or with its package in front; a query with both needs both.
 */
object AnchorMatch {
    fun matches(anchor: Anchor, query: AnchorRef): Boolean {
        if (query.path != null && !(anchor.path?.let { it == query.path || it.endsWith("/${query.path}") } ?: false)) return false
        val symbol = query.symbol ?: return true
        val named = anchor.symbol ?: return false
        return named == symbol || named.startsWith("$symbol.") || named.endsWith(".$symbol")
    }

    /** A `LIKE … ESCAPE '\'` pattern over the stored anchors that every matching fact fits (and a few others, so [matches] still decides). */
    fun prefilter(query: AnchorRef): String =
        if (query.path != null) "%\"path\":\"%${Like.escape(query.path)}\"%" else "%\"symbol\":\"%${Like.escape(query.symbol.orEmpty())}%"
}
